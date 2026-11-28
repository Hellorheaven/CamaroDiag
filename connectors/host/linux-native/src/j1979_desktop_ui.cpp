#include "j1979_desktop_commands.hpp"
#include "j1979_desktop_process.hpp"
#include <QApplication>
#include <QCheckBox>
#include <QCloseEvent>
#include <QComboBox>
#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QFileDialog>
#include <QFileInfo>
#include <QFormLayout>
#include <QFont>
#include <QTextDocument>
#include <QGroupBox>
#include <QHBoxLayout>
#include <QHeaderView>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLabel>
#include <QLineEdit>
#include <QMainWindow>
#include <QMessageBox>
#include <QPlainTextEdit>
#include <QPushButton>
#include <QScrollArea>
#include <QSplitter>
#include <QTabWidget>
#include <QTextCursor>
#include <QTimer>
#include <QTreeWidget>
#include <QVBoxLayout>
#include <algorithm>
#include <map>
#include <stdexcept>
#include <unistd.h>
namespace {
using cd::desktop::Field;
using cd::desktop::FieldKind;
using cd::desktop::Operation;
using cd::desktop::Values;
QString q(std::string_view s){return QString::fromUtf8(s.data(),static_cast<qsizetype>(s.size()));}
std::string u(const QString& s){const auto b=s.toUtf8();return {b.constData(),static_cast<std::size_t>(b.size())};}
constexpr qsizetype preview_limit=4*1024*1024;
class ReportView final:public QWidget{
    QTreeWidget* tree_{};
    QPlainTextEdit* raw_{};
    QLabel* scope_{};
    unsigned nodes_{};
    void add(QTreeWidgetItem* parent,const QString& name,const QJsonValue& value,unsigned depth){
        if(depth>12||nodes_>=5000)return;
        ++nodes_;
        auto* item=parent?new QTreeWidgetItem(parent):new QTreeWidgetItem(tree_);
        item->setText(0,name);
        if(value.isObject()){
            const auto o=value.toObject();
            item->setText(1,QString("%1 champs").arg(o.size()));
            for(auto it=o.begin();it!=o.end()&&nodes_<5000;++it)add(item,it.key(),it.value(),depth+1);
        }else if(value.isArray()){
            const auto a=value.toArray();item->setText(1,QString("%1 éléments").arg(a.size()));
            for(qsizetype i=0;i<a.size()&&nodes_<5000;++i)add(item,QString::number(i+1),a.at(i),depth+1);
        }else if(value.isNull())item->setText(1,"Non disponible");
        else if(value.isBool())item->setText(1,value.toBool()?"true":"false");
        else if(value.isString())item->setText(1,value.toString().left(4096));
        else item->setText(1,QString::number(value.toDouble(),'g',16));
    }
public:
    explicit ReportView(QWidget* parent=nullptr):QWidget(parent){
        auto* layout=new QVBoxLayout(this);
        scope_=new QLabel("Aucun fichier chargé. Un rapport affiché n’est pas une validation physique.");
        scope_->setWordWrap(true);layout->addWidget(scope_);
        auto* tabs=new QTabWidget;
        tree_=new QTreeWidget;tree_->setHeaderLabels({"Champ","Valeur"});
        tree_->header()->setSectionResizeMode(0,QHeaderView::ResizeToContents);
        tree_->header()->setStretchLastSection(true);tree_->setUniformRowHeights(true);
        raw_=new QPlainTextEdit;raw_->setReadOnly(true);raw_->setLineWrapMode(QPlainTextEdit::NoWrap);
        tabs->addTab(tree_,"Données structurées");tabs->addTab(raw_,"Aperçu du fichier");layout->addWidget(tabs);
    }
    void load(const QString& name){
        tree_->clear();raw_->clear();nodes_=0;
        QFileInfo info(name);
        if(!info.isFile()||info.isSymLink()){
            scope_->setText("Fichier régulier local non disponible.");return;
        }
        QFile f(name);
        if(!f.open(QIODevice::ReadOnly)){scope_->setText(f.errorString());return;}
        const auto bytes=f.read(preview_limit+1);
        if(bytes.size()>preview_limit){
            raw_->setPlainText(QString::fromUtf8(bytes.first(preview_limit)));
            scope_->setText("Fichier volumineux : aperçu limité à 4 Mio. Le rapport complet reste sur disque.");
            return;
        }
        raw_->setPlainText(QString::fromUtf8(bytes));
        QJsonParseError parse{};
        const auto doc=QJsonDocument::fromJson(bytes,&parse);
        if(parse.error!=QJsonParseError::NoError){
            scope_->setText("Aperçu texte/CSV ou JSON non analysable. Aucun statut de validation déduit.");
            return;
        }
        if(doc.isObject()){
            const auto o=doc.object();
            const auto schema=o.value("schema").toString();
            scope_->setText("Schéma : "+schema+" — aperçu local non authentifié, sans preuve de véhicule réel.");
            // Put key outcome fields at the top without interpreting their meaning.
            for(const auto& key:{"completed","verified","consistent","stop_reason","declared_vin","observed_vin",
                                  "vin","identity_rechecked","physical_vehicle_verified","source_sha256"})
                if(o.contains(key))add(nullptr,key,o.value(key),0);
            add(nullptr,"Rapport complet",QJsonValue(o),0);
        }else add(nullptr,"Rapport",QJsonValue(doc.array()),0);
        if(nodes_>=5000)scope_->setText(scope_->text()+" Arbre limité à 5 000 éléments.");
        tree_->expandToDepth(0);
    }
};
class Window final:public QMainWindow{
    QComboBox* operation_{};
    QFormLayout* fields_{};
    QWidget* form_{};
    QLabel* description_{};
    QLabel* status_{};
    QPlainTextEdit* preview_{};
    QPlainTextEdit* console_{};
    QPushButton *start_{},*confirm_{},*cancel_{},*open_{};
    QTimer* timer_{};
    QTabWidget* tabs_{};
    ReportView* report_{};
    std::map<std::string,QWidget*,std::less<>> widgets_;
    cd::desktop::Process process_;
    QString output_;
    QString executable_;
    bool physical_{},closing_{};
    const Operation& selected()const{return cd::desktop::operations().at(static_cast<std::size_t>(operation_->currentIndex()));}
    Values values()const{
        Values out;
        for(const auto& [key,widget]:widgets_){
            if(auto* check=qobject_cast<QCheckBox*>(widget))out.emplace(key,check->isChecked()?"true":"false");
            else if(auto* edit=qobject_cast<QLineEdit*>(widget))out.emplace(key,u(edit->text().trimmed()));
        }
        return out;
    }
    void show_preview(){
        if(process_.running())return;
        try{
            auto inv=cd::desktop::prepare(selected(),values());
            preview_->setPlainText(q(cd::desktop::preview(inv)));start_->setEnabled(true);
            status_->setText(inv.physical?"Paramètres valides. Autorisation interactive requise.":"Paramètres valides. Analyse hors ligne.");
        }catch(const std::exception& e){
            preview_->clear();start_->setEnabled(false);status_->setText(q(e.what()));
        }
    }
    void rebuild(){
        widgets_.clear();
        while(fields_->rowCount())fields_->removeRow(0);
        const auto& op=selected();description_->setText(q(op.description));
        for(const auto& f:op.fields){
            if(f.kind==FieldKind::check){
                auto* check=new QCheckBox(q(f.label));
                widgets_.emplace(f.key,check);fields_->addRow("",check);
                connect(check,&QCheckBox::toggled,this,[this]{show_preview();});continue;
            }
            auto* edit=new QLineEdit;edit->setMaxLength(4096);edit->setPlaceholderText(q(f.placeholder));
            widgets_.emplace(f.key,edit);
            if(f.kind==FieldKind::decimal&&!f.required&&!f.placeholder.empty())edit->setText(q(f.placeholder));
            auto label=q(f.label)+(f.required?" *":"");
            if(f.kind==FieldKind::input_file||f.kind==FieldKind::output_file||
               f.kind==FieldKind::new_directory||f.kind==FieldKind::existing_directory){
                auto* row=new QWidget;auto* horizontal=new QHBoxLayout(row);
                horizontal->setContentsMargins(0,0,0,0);horizontal->addWidget(edit,1);
                auto* browse=new QPushButton("Parcourir…");horizontal->addWidget(browse);
                connect(browse,&QPushButton::clicked,this,[this,edit,f]{
                    QString path;
                    if(f.kind==FieldKind::input_file)
                        path=QFileDialog::getOpenFileName(this,q(f.label),edit->text());
                    else if(f.kind==FieldKind::existing_directory)
                        path=QFileDialog::getExistingDirectory(this,q(f.label),edit->text());
                    else if(f.kind==FieldKind::output_file)
                        path=QFileDialog::getSaveFileName(this,q(f.label),edit->text(),{},nullptr,QFileDialog::DontConfirmOverwrite);
                    else{
                        const auto parent=QFileDialog::getExistingDirectory(this,"Choisir le répertoire parent");
                        if(!parent.isEmpty())path=QDir(parent).filePath("camarodiag-journals");
                    }
                    if(!path.isEmpty())edit->setText(path);
                });
                fields_->addRow(label,row);
            }else fields_->addRow(label,edit);
            connect(edit,&QLineEdit::textChanged,this,[this]{show_preview();});
        }
        show_preview();
    }
    void append_console(const QString& data){
        if(data.isEmpty())return;
        auto cursor=console_->textCursor();cursor.movePosition(QTextCursor::End);cursor.insertText(data);
        if(console_->document()->characterCount()>250000){
            cursor.movePosition(QTextCursor::Start);
            cursor.movePosition(QTextCursor::Right,QTextCursor::KeepAnchor,console_->document()->characterCount()-200000);
            cursor.removeSelectedText();cursor.insertText("[Début du journal affiché tronqué]\n");
        }
        cursor.movePosition(QTextCursor::End);console_->setTextCursor(cursor);console_->ensureCursorVisible();
    }
    void set_running(bool running){
        operation_->setEnabled(!running);form_->setEnabled(!running);
        start_->setEnabled(!running);cancel_->setEnabled(running);confirm_->setEnabled(false);
    }
    void launch(){
        try{
            const auto current=values();const auto inv=cd::desktop::prepare(selected(),current);
            output_=q(current.at("output"));physical_=inv.physical;
            if(physical_){
                const auto consent=QMessageBox::question(this,"Périmètre véhicule",
                    "Véhicule immobilisé, véhicule et calculateur autorisés, interface sélectionnée et périmètre vérifiés ?\n\n"
                    "Le moteur demandera encore votre confirmation avant de communiquer.",
                    QMessageBox::Yes|QMessageBox::No,QMessageBox::No);
                if(consent!=QMessageBox::Yes)return;
            }
            console_->clear();append_console(q(cd::desktop::preview(inv))+"\n");
            process_.start(u(executable_),inv);
            set_running(true);tabs_->setCurrentIndex(0);timer_->start(40);
            status_->setText(physical_?"En attente du moteur et de son consentement.":"Analyse hors ligne en cours.");
        }catch(const std::exception& e){QMessageBox::warning(this,"Exécution refusée",q(e.what()));show_preview();}
    }
    void tick(){
        try{
            append_console(q(process_.drain()));
            confirm_->setEnabled(process_.awaiting_consent());
            if(process_.awaiting_consent())status_->setText("Le moteur demande le consentement. Vérifiez le message puis confirmez explicitement.");
            int code{};
            if(!process_.poll(code))return;
            append_console(q(process_.drain()));
            timer_->stop();set_running(false);
            status_->setText(code==0?"Commande terminée avec code 0 — examiner le rapport.":
                QString("Commande interrompue ou incomplète (code %1). Aucun réessai automatique.").arg(code));
            append_console(QString("\n[Code de sortie : %1]\n").arg(code));
            if(QFileInfo(output_).isFile()){report_->load(output_);tabs_->setCurrentIndex(1);}
            if(closing_)close();
        }catch(const std::exception& e){
            process_.cancel();status_->setText(q(e.what()));confirm_->setEnabled(false);
        }
    }
protected:
    void closeEvent(QCloseEvent* event)override{
        if(!process_.running()){event->accept();return;}
        if(QMessageBox::question(this,"Campagne active","Demander l’arrêt local puis fermer après la sortie du moteur ?",
            QMessageBox::Yes|QMessageBox::No,QMessageBox::No)==QMessageBox::Yes){
            closing_=true;process_.cancel();confirm_->setEnabled(false);cancel_->setEnabled(false);
        }
        event->ignore();
    }
public:
    Window(){
        setWindowTitle("CamaroDiag — SAE J1979 Linux");resize(1180,820);
        executable_=QDir(QCoreApplication::applicationDirPath()).filePath("camarodiag-native");
        auto* central=new QWidget;auto* main=new QVBoxLayout(central);
        auto* title=new QLabel("SAE J1979 · Diagnostic et preuves locales");
        QFont font=title->font();font.setPointSize(font.pointSize()+5);font.setBold(true);title->setFont(font);main->addWidget(title);
        auto* notice=new QLabel("Lecture seule dans cette interface. Les opérations de maintenance restent distinctes dans la CLI. Aucun succès affiché ne constitue à lui seul une validation physique.");
        notice->setWordWrap(true);main->addWidget(notice);
        auto* splitter=new QSplitter(Qt::Horizontal);main->addWidget(splitter,1);
        auto* left=new QWidget;auto* options=new QVBoxLayout(left);
        operation_=new QComboBox;
        for(const auto& op:cd::desktop::operations())operation_->addItem(q(op.title));
        options->addWidget(operation_);
        description_=new QLabel;description_->setWordWrap(true);options->addWidget(description_);
        auto* scroll=new QScrollArea;scroll->setWidgetResizable(true);
        form_=new QWidget;fields_=new QFormLayout(form_);fields_->setFieldGrowthPolicy(QFormLayout::AllNonFixedFieldsGrow);
        scroll->setWidget(form_);options->addWidget(scroll,1);
        auto* box=new QGroupBox("Commande native (aperçu uniquement)");auto* command_layout=new QVBoxLayout(box);
        preview_=new QPlainTextEdit;preview_->setReadOnly(true);preview_->setMaximumHeight(130);
        command_layout->addWidget(preview_);options->addWidget(box);
        auto* buttons=new QHBoxLayout;
        start_=new QPushButton("Démarrer");confirm_=new QPushButton("Confirmer la lecture");
        cancel_=new QPushButton("Arrêter");buttons->addWidget(start_);buttons->addWidget(confirm_);buttons->addWidget(cancel_);
        options->addLayout(buttons);splitter->addWidget(left);
        auto* right=new QWidget;auto* evidence=new QVBoxLayout(right);
        tabs_=new QTabWidget;console_=new QPlainTextEdit;console_->setReadOnly(true);
        console_->setMaximumBlockCount(3000);report_=new ReportView;
        tabs_->addTab(console_,"Exécution locale");tabs_->addTab(report_,"Rapport");evidence->addWidget(tabs_,1);
        open_=new QPushButton("Ouvrir un rapport existant…");evidence->addWidget(open_);
        splitter->addWidget(right);splitter->setSizes({510,650});
        status_=new QLabel;status_->setWordWrap(true);main->addWidget(status_);setCentralWidget(central);
        timer_=new QTimer(this);
        connect(timer_,&QTimer::timeout,this,[this]{tick();});
        connect(operation_,&QComboBox::currentIndexChanged,this,[this]{rebuild();});
        connect(start_,&QPushButton::clicked,this,[this]{launch();});
        connect(confirm_,&QPushButton::clicked,this,[this]{
            if(process_.approve())status_->setText("Consentement local transmis. Aucun réessai automatique.");
            else status_->setText("Consentement non transmis. Vérifiez le résultat de la commande.");
            confirm_->setEnabled(false);
        });
        connect(cancel_,&QPushButton::clicked,this,[this]{
            process_.cancel();confirm_->setEnabled(false);cancel_->setEnabled(false);status_->setText("Arrêt demandé au moteur ; attendre la fin de la commande.");
        });
        connect(open_,&QPushButton::clicked,this,[this]{
            const auto path=QFileDialog::getOpenFileName(this,"Ouvrir un rapport local",{}, "Rapports (*.json *.csv);;Tous les fichiers (*)");
            if(!path.isEmpty()){report_->load(path);tabs_->setCurrentIndex(1);}
        });
        set_running(false);rebuild();
    }
};
}
int main(int argc,char** argv){
    QApplication app(argc,argv);
    app.setApplicationName("CamaroDiag J1979");app.setOrganizationName("CamaroDiag");
    const bool smoke=argc==2&&std::string_view(argv[1])=="--self-test-ui";
    if(geteuid()==0&&!smoke){
        QMessageBox::critical(nullptr,"Droits administrateur","Ne pas exécuter cette interface graphique en root. Configurez les droits locaux sur le connecteur.");
        return 2;
    }
    Window window;
    if(smoke){QTimer::singleShot(0,&app,&QApplication::quit);return app.exec();}
    window.show();return app.exec();
}
