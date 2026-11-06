package com.andrerinas.openheadunit.main

import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Mock-ups of the simplified DiAuto for MG4 screens, written to app/build/screenshots/. */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-night-mdpi")
class MockupScreenshotTest {

    private val context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)

    private fun save(view: View, name: String, width: Int = 1920, height: Int = 720) {
        repeat(2) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun homeWaiting() = home(
        "En attente du téléphone",
        "Bluetooth et Wi‑Fi activés sur le téléphone, il se connecte tout seul.",
        "Connecter le téléphone",
        "mockup_home_waiting.png",
    )

    @Test fun homeConnected() = home(
        "Connecté à Pixel 8",
        "Android Auto tourne en arrière-plan.",
        "Ouvrir Android Auto",
        "mockup_home_connected.png",
    )

    private fun home(state: String, hint: String, action: String, name: String) {
        val root = LayoutInflater.from(context).inflate(R.layout.da_home, null)
        root.findViewById<TextView>(R.id.home_version).text = "2.1.0"
        root.findViewById<TextView>(R.id.home_state).text = state
        root.findViewById<TextView>(R.id.home_state_hint).text = hint
        root.findViewById<TextView>(R.id.home_main_label).text = action
        root.findViewById<TextView>(R.id.home_update_hint).text = "Vous êtes à jour"
        save(root, name)
    }

    @Test fun projectionMenu() {
        val ctx = context
        val frame = FrameLayout(ctx)
        // Stand-in for the Android Auto picture under the menu.
        frame.addView(View(ctx).apply { setBackgroundColor(0xFF2E5E6E.toInt()) })
        frame.addView(LayoutInflater.from(ctx).inflate(R.layout.da_projection_menu, frame, false))
        save(frame, "mockup_projection_menu.png")
    }

    private sealed class Control {
        class Switch(val on: Boolean) : Control()
        class Choices(val labels: List<String>, val selected: Int) : Control()
        class Value(val text: String) : Control()
        class Button(val text: String, val danger: Boolean = false) : Control()
        class Level(val value: Float) : Control()
    }

    private class Row(val title: String, val summary: String, val control: Control)

    private val tabs = listOf(
        "Connexion" to R.drawable.ic_wifi,
        "Écran" to R.drawable.ic_da_display,
        "Son" to R.drawable.ic_volume,
        "Voiture" to R.drawable.ic_directions_car,
        "À propos" to R.drawable.ic_da_about,
    )

    @Test fun settingsConnection() = settings(0, listOf(
        Row("Android Auto sans fil", "Par le point d'accès de la voiture. Le câble USB marche toujours.", Control.Switch(true)),
        Row("Démarrer avec la voiture", "Android Auto revient tout seul quand vous montez.", Control.Switch(true)),
        Row("Téléphone", "Celui qui lance Android Auto au démarrage.", Control.Value("Pixel 8")),
        Row("Bande du point d'accès", "5 GHz : image plus fluide. 2,4 GHz si le téléphone ne trouve pas la voiture.", Control.Choices(listOf("5 GHz", "2,4 GHz"), 0)),
    ), "mockup_settings_connexion.png")

    @Test fun settingsDisplay() = settings(1, listOf(
        Row("Images par seconde", "30 suffit en voiture et soulage le téléphone.", Control.Choices(listOf("30", "60"), 0)),
        Row("Taille de l'interface", "Taille des textes et des boutons d'Android Auto.", Control.Choices(listOf("Petite", "Normale", "Grande"), 1)),
        Row("Mode nuit", "Couleurs sombres d'Android Auto.", Control.Choices(listOf("Comme la voiture", "Jour", "Nuit"), 0)),
    ), "mockup_settings_ecran.png")

    @Test fun settingsSound() = settings(2, listOf(
        Row("Séparer les flux audio", "Le guidage a son propre canal et s'entend même sur la radio.", Control.Switch(true)),
        Row("Volume du guidage", "Par rapport à la musique.", Control.Level(0.5f)),
        Row("Volume de l'assistant", "Par rapport à la musique.", Control.Level(0.5f)),
        Row("Musique par Bluetooth", "La musique passe par le Bluetooth de la voiture, Android Auto garde le reste.", Control.Switch(false)),
        Row("Réserve audio Wi‑Fi", "Plus grande : moins de coupures, pause un peu plus lente.", Control.Choices(listOf("0,5 s", "1 s", "1,5 s"), 1)),
    ), "mockup_settings_son.png")

    @Test fun settingsCar() = settings(3, listOf(
        Row("Batterie", "Capacité utilisée pour l'autonomie dans Maps.", Control.Value("64 kWh · détectée")),
        Row("État de santé (SOH)", "Lu dans MG4 Dash, ou réglé à la main.", Control.Value("97 % · MG4 Dash")),
        Row("Autonomie dans Google Maps", "Envoie la charge et l'autonomie de la voiture à Maps.", Control.Switch(true)),
        Row("Boutons du volant", "Derniers codes reçus, pour vérifier qu'ils arrivent.", Control.Value("300 · 299 · 301")),
    ), "mockup_settings_voiture.png")

    @Test fun settingsAbout() = settings(4, listOf(
        Row("DiAuto for MG4 2.1.0", "Vous êtes à jour.", Control.Button("Mises à jour")),
        Row("Recevoir les bêtas", "Versions de test, avant leur sortie.", Control.Switch(false)),
        Row("Rapport de compatibilité", "Votre configuration en bref, à joindre à une issue GitHub.", Control.Button("Ouvrir")),
        Row("Logs", "Toujours enregistrés. À joindre si quelque chose ne va pas.", Control.Button("Exporter")),
        Row("Revenir à l'Android Auto de la voiture", "Désinstalle DiAuto et remet AllGo.", Control.Button("Revenir", danger = true)),
    ), "mockup_settings_apropos.png")

    private fun settings(selected: Int, rows: List<Row>, name: String) {
        val ctx = context
        val inflater = LayoutInflater.from(ctx)
        val root = inflater.inflate(R.layout.da_settings, null)
        val tabBox = root.findViewById<LinearLayout>(R.id.settings_tabs)
        val accent = ContextCompat.getColor(ctx, R.color.da_accent)
        val text = ContextCompat.getColor(ctx, R.color.da_text)
        tabs.forEachIndexed { i, (label, icon) ->
            val tab = inflater.inflate(R.layout.da_settings_tab, tabBox, false)
            tab.findViewById<ImageView>(R.id.tab_icon).apply {
                setImageResource(icon)
                if (i == selected) imageTintList = ColorStateList.valueOf(accent)
            }
            tab.findViewById<TextView>(R.id.tab_label).apply {
                this.text = label
                if (i == selected) setTextColor(text)
            }
            if (i == selected) tab.setBackgroundResource(R.drawable.da_tab_selected)
            tabBox.addView(tab)
        }
        val list = root.findViewById<LinearLayout>(R.id.settings_list)
        rows.forEach { row ->
            val v = inflater.inflate(R.layout.da_settings_row, list, false)
            v.findViewById<TextView>(R.id.row_title).text = row.title
            v.findViewById<TextView>(R.id.row_summary).apply {
                this.text = row.summary
                visibility = if (row.summary.isEmpty()) View.GONE else View.VISIBLE
            }
            v.findViewById<FrameLayout>(R.id.row_control).addView(control(ctx, row.control))
            list.addView(v)
        }
        save(root, name)
    }

    private fun control(ctx: android.content.Context, c: Control): View {
        val accent = ContextCompat.getColor(ctx, R.color.da_accent)
        val surface = ContextCompat.getColor(ctx, R.color.da_surface)
        val bg = ContextCompat.getColor(ctx, R.color.da_background)
        val textCol = ContextCompat.getColor(ctx, R.color.da_text)
        return when (c) {
            is Control.Switch -> control(ctx, Control.Choices(listOf("Non", "Oui"), if (c.on) 1 else 0))
            is Control.Choices -> MaterialButtonToggleGroup(ctx).apply {
                isSingleSelection = true
                c.labels.forEachIndexed { i, label ->
                    val b = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        id = View.generateViewId()
                        text = label
                        isAllCaps = false
                        textSize = 19f
                        minWidth = 130
                        minimumHeight = 72
                        val on = i == c.selected
                        setTextColor(if (on) bg else textCol)
                        backgroundTintList = ColorStateList.valueOf(if (on) accent else surface)
                        strokeColor = ColorStateList.valueOf(0xFF2A384B.toInt())
                    }
                    addView(b, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 72))
                }
            }
            is Control.Value -> TextView(ctx).apply {
                text = c.text + "  ›"
                textSize = 21f
                setTextColor(accent)
            }
            is Control.Button -> MaterialButton(ctx).apply {
                text = c.text
                isAllCaps = false
                textSize = 20f
                minWidth = 200
                setTextColor(if (c.danger) 0xFFFFFFFF.toInt() else bg)
                backgroundTintList = ColorStateList.valueOf(if (c.danger) 0xFFB3404A.toInt() else accent)
                cornerRadius = 22
            }
            is Control.Level -> Slider(ctx).apply {
                valueFrom = 0f; valueTo = 1f; value = c.value
                layoutParams = FrameLayout.LayoutParams(420, ViewGroup.LayoutParams.WRAP_CONTENT)
                thumbTintList = ColorStateList.valueOf(accent)
                trackActiveTintList = ColorStateList.valueOf(accent)
                trackInactiveTintList = ColorStateList.valueOf(0xFF2A384B.toInt())
            }
        }
    }
}
