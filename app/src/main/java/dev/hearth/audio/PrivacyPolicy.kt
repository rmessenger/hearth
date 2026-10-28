package dev.hearth.audio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PrivacyPolicyDialog(dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Privacy policy") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Privacy policy for Hearth", style = MaterialTheme.typography.titleSmall)
            Text("Effective October 27, 2026. Hearth is an offline audiobook player developed by Robin Messenger.")
            Text("Your data stays on your device", style = MaterialTheme.typography.titleSmall)
            Text("Hearth does not collect or transmit your personal data. It has no account system, ads, analytics, tracking, or developer-operated servers. The app does not request internet access.")
            Text("Audiobooks and folder access", style = MaterialTheme.typography.titleSmall)
            Text("You choose a folder using Android\u2019s system folder picker. Hearth reads audio files, cover images, file names, and embedded metadata in that folder to display your library and play your audiobooks. These files are not uploaded to the developer. Hearth does not delete or modify your original audiobook files.")
            Text("Local storage and device features", style = MaterialTheme.typography.titleSmall)
            Text("Hearth stores library information, playback positions, preferences, bookmarks, character notes, listening history, and listening totals in its private storage on your device. It uses background media playback and system media controls so audio can continue with the screen off. If you enable shake controls, motion sensor readings are used on the device to control the sleep timer; they are not retained or transmitted. Book titles and artwork may appear in Android\u2019s media controls and on connected playback devices.")
            Text("Retention and deletion", style = MaterialTheme.typography.titleSmall)
            Text("Local data remains until you clear Hearth\u2019s app storage in Android settings or uninstall the app. Doing either removes saved progress, notes, and preferences but leaves your original audiobook files in place. Hearth disables Android cloud backup and device transfer for its private app data. Folder access can be revoked through Android settings.")
            Text("Sharing and external services", style = MaterialTheme.typography.titleSmall)
            Text("Hearth does not sell or share your personal data with the developer or third parties. Android and Google Play may process information under their own policies when you install, update, or use their services. This policy page is hosted by GitHub Pages, whose own privacy policy applies to website access. If you contact the developer, the information you choose to send is used to respond and resolve your request, and is kept only as long as needed for that purpose.")
            Text("Children and changes", style = MaterialTheme.typography.titleSmall)
            Text("Hearth does not knowingly collect personal information from children. Changes to this policy will be published here with an updated effective date.")
            Text("Contact", style = MaterialTheme.typography.titleSmall)
            Text("For privacy questions or requests about information you have sent to the developer, contact Robin Messenger at rmessenger@gmail.com.")
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })
}
