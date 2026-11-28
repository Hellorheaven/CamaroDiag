package com.camarodiag.gen5.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.camarodiag.connectors.J1979LiveScalarProjection
import com.camarodiag.gen5.data.obd.CamaroDiagConnectorLiveCampaign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun J1979NativeScalarPanel(evidence:CamaroDiagConnectorLiveCampaign.Evidence,
    enabled:Boolean,fr:Boolean,onExport:(Boolean)->Unit) {
    var projection by remember(evidence) { mutableStateOf<J1979LiveScalarProjection.Projection?>(null) }
    var unavailable by remember(evidence) { mutableStateOf(false) }
    LaunchedEffect(evidence) {
        if(!evidence.captureIntegrityVerified)return@LaunchedEffect
        val derived=withContext(Dispatchers.IO) {
            runCatching {
                val replay=CamaroDiagConnectorLiveCampaign.verifiedReplay(evidence)
                J1979LiveScalarProjection.project(replay,evidence.target.channel.vehicle)
            }.getOrNull()
        }
        projection=derived;unavailable=derived==null
    }
    Column {
        Text(if(fr)"Projection numérique CL/1" else "CL/1 numerical projection")
        Text(if(fr)"Valeurs scalaires documentées uniquement. Les échelles ECU non enregistrées restent indéterminées."
            else "Documented scalar values only. Unrecorded ECU scales remain unknown.",fontSize=11.sp)
        if(unavailable)Text(if(fr)"Capture indisponible ou modifiée." else "Capture unavailable or changed.")
        val p=projection
        if(p!=null) {
            if(!p.identityRechecked)Text(if(fr)"Identité finale non vérifiée : aucune valeur numérique."
                else "Final identity not verified: no numerical values.")
            fun display(n:Double?)=n?.let { "%.3f".format(Locale.getDefault(),it) }?:"—"
            for(c in p.channels) {
                val pid="%02X".format(Locale.ROOT,c.pid)
                Text(pid+" · "+c.name.orEmpty()+" · "+c.count+"/"+c.requested)
                Text("Min "+display(c.minimum)+" · Max "+display(c.maximum)+" · "+
                    (if(fr)"Moy. " else "Mean ")+display(c.mean)+" "+c.unit.orEmpty(),fontSize=11.sp)
                if(c.count<c.requested)Text((if(fr)"Manquants : " else "Missing: ")+c.missing+
                    " · "+c.outcomes.entries.joinToString(" · ") { it.key+": "+it.value },fontSize=11.sp)
            }
            OutlinedButton(onClick={onExport(false)},enabled=enabled,modifier=Modifier.fillMaxWidth()){
                Text(if(fr)"Exporter la projection JSON" else "Export JSON projection")
            }
            OutlinedButton(onClick={onExport(true)},enabled=enabled,modifier=Modifier.fillMaxWidth()){
                Text(if(fr)"Exporter les valeurs CSV" else "Export numerical CSV")
            }
        }
    }
}
