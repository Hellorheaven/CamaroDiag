using System.Drawing;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;

namespace CamaroDiag.J2534Bridge;

internal sealed partial class BridgeDesktopForm
{
    private readonly Button functionalProjectCatalogue=Button(J1979RecipeText("CatalogueProject"));
    private sealed record CatalogueInputs(string Catalogue,string Reference,string Pin,string Report,string Vin,string Output);

    private CatalogueInputs? ChooseCatalogueProjection()
    {
        using var dialog=new Form{Text=J1979RecipeText("CatalogueProject"),StartPosition=FormStartPosition.CenterParent,
            ClientSize=new Size(700,420),MinimumSize=new Size(600,420),MaximizeBox=false,MinimizeBox=false};
        using var grid=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(12),ColumnCount=3,RowCount=9};
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,30));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,55));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,15));
        for(int i=0;i<6;i++)grid.RowStyles.Add(new RowStyle(SizeType.Absolute,32));
        grid.RowStyles.Add(new RowStyle(SizeType.Percent,100));
        grid.RowStyles.Add(new RowStyle(SizeType.Absolute,32));grid.RowStyles.Add(new RowStyle(SizeType.Absolute,42));
        TextBox Field(string key,int index,string value="",int maxLength=32767)
        {
            grid.Controls.Add(new Label{Text=J1979RecipeText(key),Dock=DockStyle.Fill,
                TextAlign=ContentAlignment.MiddleLeft},0,index);
            var field=new TextBox{Text=value,MaxLength=maxLength,Dock=DockStyle.Fill};
            grid.Controls.Add(field,1,index);return field;
        }
        var catalogue=Field("CatalogueFile",0);
        var reference=Field("CatalogueReference",1);
        var pin=Field("CataloguePin",2,maxLength:64);
        var report=Field("CatalogueRawReport",3);
        var vin=Field("LiveVin",4,functionalEvapVin.Text.Trim(),17);
        vin.CharacterCasing=CharacterCasing.Upper;
        var output=Field("CatalogueOutput",5);
        grid.SetColumnSpan(pin,2);grid.SetColumnSpan(vin,2);
        void Browse(TextBox field,int row,bool isOutput=false,bool any=false)
        {
            var button=new Button{Text=J1979RecipeText("CatalogueBrowse"),Dock=DockStyle.Fill};
            button.Click+=(_,_)=>
            {
                if(isOutput)
                {
                    using var picker=new SaveFileDialog{Filter=J1979RecipeText("JsonFilter"),
                        FileName="CamaroDiag_J1979_Catalogue.json",AddExtension=true,DefaultExt="json",OverwritePrompt=true};
                    if(picker.ShowDialog(dialog)==DialogResult.OK)field.Text=picker.FileName;
                }
                else
                {
                    using var picker=new OpenFileDialog{CheckFileExists=true,Multiselect=false,
                        Filter=any?J1979RecipeText("CatalogueAnyFile"):J1979RecipeText("JsonFilter")};
                    if(picker.ShowDialog(dialog)==DialogResult.OK)field.Text=picker.FileName;
                }
            };
            grid.Controls.Add(button,2,row);
        }
        Browse(catalogue,0);Browse(reference,1,any:true);Browse(report,3);Browse(output,5,true);
        var note=new Label{Text=J1979RecipeText("CatalogueScope"),Dock=DockStyle.Fill};
        grid.Controls.Add(note,0,6);grid.SetColumnSpan(note,3);
        var confirm=new CheckBox{Text=J1979RecipeText("CatalogueConfirm"),Dock=DockStyle.Fill};
        grid.Controls.Add(confirm,0,7);grid.SetColumnSpan(confirm,3);
        var run=Button(J1979RecipeText("CatalogueProject"));
        var cancel=Button(J1979RecipeText("LegacyCancel"));cancel.DialogResult=DialogResult.Cancel;
        var buttons=new FlowLayoutPanel{Dock=DockStyle.Fill};
        buttons.Controls.Add(run);buttons.Controls.Add(cancel);grid.Controls.Add(buttons,0,8);grid.SetColumnSpan(buttons,3);
        CatalogueInputs? Candidate()
        {
            if(!confirm.Checked||!Regex.IsMatch(pin.Text,@"\A[0-9a-f]{64}\z")||
                !Regex.IsMatch(vin.Text,@"\A[A-HJ-NPR-Z0-9]{17}\z"))return null;
            try
            {
                var paths=new[]{catalogue.Text,reference.Text,report.Text,output.Text}.Select(Path.GetFullPath).ToArray();
                if(paths.Distinct(StringComparer.OrdinalIgnoreCase).Count()!=4||
                    !paths.Take(3).All(File.Exists)||File.Exists(paths[3])||Directory.Exists(paths[3]))return null;
                return new(paths[0],paths[1],pin.Text,paths[2],vin.Text,paths[3]);
            }
            catch(ArgumentException){return null;}
            catch(NotSupportedException){return null;}
        }
        void Refresh()=>run.Enabled=Candidate() is not null;
        foreach(var field in new[]{catalogue,reference,pin,report,vin,output})
            field.TextChanged+=(_,_)=>{confirm.Checked=false;Refresh();};
        confirm.CheckedChanged+=(_,_)=>Refresh();
        CatalogueInputs? selected=null;
        run.Click+=(_,_)=>{if((selected=Candidate()) is not null)dialog.DialogResult=DialogResult.OK;};
        dialog.Controls.Add(grid);dialog.CancelButton=cancel;Refresh();
        return dialog.ShowDialog(this)==DialogResult.OK?selected:null;
    }

    private async Task ProjectWindowsCatalogueAsync()
    {
        if(functionalCancellation is not null||busy||DateTimeOffset.UtcNow>=functionalAuthorizedUntil)return;
        var expiry=functionalAuthorizedUntil;
        var options=ChooseCatalogueProjection();
        if(options is null||DateTimeOffset.UtcNow>=expiry||functionalAuthorizedUntil!=expiry||busy)return;
        functionalProjectCatalogue.Enabled=false;
        // No device or bridge API is touched. The same offline engine is
        // available from the CLI. Do not write a projection after private
        // access has expired or changed while the inputs were being read.
        try
        {
            var result=await Task.Run(()=>ObdJ1979CatalogueProjection.ProjectFiles(options.Catalogue,options.Reference,
                options.Pin,options.Report,options.Vin));
            if(DateTimeOffset.UtcNow>=expiry||functionalAuthorizedUntil!=expiry||IsDisposed)return;
            ObdJ1979CatalogueProjection.WriteNew(options.Output,result);
            var output=new StringBuilder();
            output.AppendLine(J1979RecipeText("CatalogueSaved"));
            output.AppendLine(options.Output);
            if(result["counts"] is SortedDictionary<string,int> counts)
                foreach(var entry in counts)output.AppendLine(entry.Key+" : "+entry.Value.ToString(CultureInfo.CurrentCulture));
            output.AppendLine(J1979RecipeText("CatalogueScope"));
            MessageBox.Show(this,output.ToString(),J1979RecipeText("CatalogueProject"),MessageBoxButtons.OK,MessageBoxIcon.Information);
        }
        catch(Exception e) when(e is IOException or InvalidDataException or UnauthorizedAccessException or ArgumentException or
            System.Text.Json.JsonException or FormatException or InvalidOperationException or KeyNotFoundException or OverflowException)
        {
            if(!IsDisposed&&DateTimeOffset.UtcNow<expiry&&functionalAuthorizedUntil==expiry)
                ShowError(J1979RecipeText("CatalogueRejected")+" ("+e.GetType().Name+")");
        }
        finally{if(!IsDisposed)functionalProjectCatalogue.Enabled=DateTimeOffset.UtcNow<functionalAuthorizedUntil&&functionalRunLive.Enabled;}
    }
}
