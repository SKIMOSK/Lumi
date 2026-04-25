package com.lumi.app.ui

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.lumi.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class PatchNotesActivity : AppCompatActivity() {

    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    // Raw markdown is fetched from main branch; falls back to empty if unavailable.
    private val patchNotesUrl =
        "https://raw.githubusercontent.com/skimosk/Lumi/main/PATCH_NOTES.md"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patch_notes)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scrollView  = findViewById<View>(R.id.scrollView)
        val layoutLoading = findViewById<LinearLayout>(R.id.layoutLoading)
        val tvPatch     = findViewById<TextView>(R.id.tvPatchNotes)
        val tvCaps      = findViewById<TextView>(R.id.tvCapabilities)

        tvCaps.text = CAPABILITIES_TEXT

        lifecycleScope.launch {
            val markdown = withContext(Dispatchers.IO) { fetchPatchNotes() }
            layoutLoading.visibility = View.GONE
            scrollView.visibility = View.VISIBLE
            if (markdown != null) {
                tvPatch.text = renderMarkdown(markdown)
            } else {
                tvPatch.text = "Nu s-au putut încărca noutățile.\nVerifică conexiunea la internet."
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun fetchPatchNotes(): String? = try {
        val req = Request.Builder()
            .url(patchNotesUrl)
            .header("Cache-Control", "no-cache")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Exception) { null }

    // Light markdown renderer: converts headers, bullets, bold to plain readable text.
    private fun renderMarkdown(md: String): String {
        return md.lines().joinToString("\n") { line ->
            when {
                line.startsWith("## ") -> "\n▸ ${line.removePrefix("## ").uppercase()}"
                line.startsWith("# ")  -> "\n━━━ ${line.removePrefix("# ").uppercase()} ━━━"
                line.startsWith("### ") -> "\n  ${line.removePrefix("### ")}"
                line.startsWith("- ") || line.startsWith("* ") ->
                    "  • ${line.removePrefix("- ").removePrefix("* ")}"
                else -> line.replace("**", "").replace("*", "").replace("`", "")
            }
        }.trim()
    }

    companion object {
        private val CAPABILITIES_TEXT = """
MESAGERIE & COMUNICARE
  • WhatsApp — trimite mesaje, imagini, fișiere
  • Telegram — mesaje și imagini
  • SMS — mesaje text
  • Instagram DM — mesaje directe
  • Snapchat, Facebook Messenger, Discord, Slack
  • Email (Gmail) — compune și trimite
  • Apeluri telefonice

NOTIFICĂRI & RĂSPUNS RAPID
  • Citește notificările recente din orice aplicație
  • Răspunde direct la mesaje din notificări ([replyable])
    fără a deschide aplicația (REPLY_NOTIFICATION)

NAVIGARE & LOCAȚIE
  • Google Maps și Waze — deschide ruta direct
  • Locații salvate: "du-mă acasă/la birou/la sală"
  • Indicii de locație: "lângă Primărie", "în centru"
    → caută automat punctul de reper cu GPS + OpenStreetMap
  • Salvare automată: prima oară cere adresa, pe urmă o ține minte

TIMERE, ALARME & CRONOMETRU
  • Setează timer cu nume personalizat
  • Oprește/pauze/reia/resetează — după nume sau index ("al doilea timer")
  • Alarme în aplicația Ceas
  • Cronometru

CALENDAR
  • Citește evenimentele viitoare
  • Creează evenimente noi
  • Șterge sau modifică evenimente existente (după ID sau titlu)

NOTIȚE
  • Creează notițe în Lumi, Samsung Notes, Google Keep, OneNote, Obsidian
  • Caută notițe după cuvânt cheie (cu ID pentru editare/ștergere)
  • Editează sau șterge notițe existente

GALERIE FOTO
  • Caută imagini după descriere, dată, perioadă
  • Trimite imaginile găsite pe WhatsApp, Telegram, Instagram
  • "Caută și trimite" în același mesaj
  • "Cea mai recentă poză", "a doua cea mai recentă" etc.

FIȘIERE & DOCUMENTE
  • Citește PDF, DOCX, TXT — rezumă sau răspunde la întrebări
  • Trimite fișier atașat via butonul clip
  • Caută fișier pe telefon după nume și trimite-l
  • Editează fișiere text atașate
  • Creează versiune modificată și trimite

SETĂRI SISTEM
  • Luminozitate (absolut sau relativ)
  • Volum media/sonerie (absolut, up/down/mute/max)
  • Nu Deranja (activează/dezactivează)
  • Economisire baterie
  • Viteза vorbirii TTS

MEDIA & DIVERTISMENT
  • Spotify, YouTube Music, Netflix — play/pauza/skip/caută
  • YouTube — caută, Watch Later
  • Stiri Google News

ALTELE
  • Navigare GPS: Google Maps sau Waze
  • VPN: Surfshark, NordVPN
  • Smart Home: Google Home
  • Sănătate: Google Fit, Strava, MyFitnessPal
  • Bancar: Revolut, BTpay, PayPal, Wise (deschide aplicația)
  • Crypto: Binance, Coinbase (deschide aplicația)
  • Shopping: Amazon, eBay, AliExpress (caută produse)
  • Livrare mâncare: Uber Eats, DoorDash, Deliveroo
  • Rideshare: Uber, Lyft
  • Bluetooth: listare, conectare, deconectare, asociere
  • Memorie utilizator: preferințe, locații, fapte personale

MAȘINA (Android Auto / Apple CarPlay)
  • Navigarea și media pornite pe telefon apar automat
    pe ecranul mașinii dacă Android Auto e conectat
  • Funcționează: Maps, Waze, Spotify, YouTube Music, apeluri

CE NU FACE LUMI
  • Nu plasează comenzi sau cumpărături
  • Nu efectuează transferuri bancare sau crypto
  • Nu trimite fără confirmare ta (dacă Mod Autonom e dezactivat)
        """.trimIndent()
    }
}
