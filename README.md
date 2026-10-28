# Flüssle

*Flüsse. Werte. Sicherheit.*

Android-App (Kotlin), die die Flusspegel des Landes Vorarlberg auf einer OpenStreetMap-Karte zeigt,
eingefärbt nach Abflussklasse (niedrig, normal, HQ1, HQ5, ...) und mit Pfeil für die Tendenz. Kein eigener Server nötig: die App
liest die öffentlichen Seiten von [vowis.vorarlberg.at](https://vowis.vorarlberg.at/stationswrapper) direkt.

## Aufbau

- `core/`: reine Kotlin-Datenlogik ohne Android (Abruf, JSON/CSV/HTML-Auswertung, Abflussklassen, Alarm-Regeln). Mit Unit-Tests.
- `app/`: Android-Oberfläche:
  - Karte (osmdroid) mit Zoom, Standort und Legende; Kartenstil Standard oder Gelände
  - Stationskarte mit Wert, Klasse, Mini-Verlauf der letzten 3 Tage und Tendenz; voller Verlauf als Diagramm
  - Hauptpegel (Stern), der beim Start geöffnet wird
  - Liste aller Pegel nach Gefahr sortiert
  - Alarme pro Pegel oder Fluss (Hintergrundprüfung alle 15 min mit WorkManager)
  - einstellbare Schriftgröße („Aa Ansicht“) für gute Lesbarkeit

## Starten

1. Ordner in **Android Studio** öffnen (File → Open), Gradle-Sync abwarten.
2. Einen Emulator anlegen (Device Manager) oder ein Handy per USB verbinden, dann ▶ Run.
3. APK bauen: Build → Build App Bundle(s) / APK(s) → Build APK(s). Die Datei liegt dann unter
   `app/build/outputs/apk/debug/app-debug.apk` und kann aufs Handy kopiert und installiert werden.

Fertige APKs: unter **Releases** auf GitHub. Jeder Push auf `main` baut die APK automatisch (Actions → Artefakt),
ein Tag wie `v0.3` legt zusätzlich ein Release mit der APK an.

Auf der Kommandozeile (mit JDK 21, nicht dem Java 25 aus Android Studio): `gradlew assembleDebug`. Nur die Datenlogik testen (ohne Android SDK):
`gradlew -PcoreOnly :core:test`.

## Daten

- Stationen, Lage, aktuelle Werte, HQ1–HQ30, Klasse und Trend: GeoServer-Dienst von vowis (`/geoserver/owf/ows`, Layer `owf:Pegel`, GeoJSON).
- Fluss und Flussgebiet: Stationstabelle `tbl_Abflussstationen.aspx`.
- Abfluss-Verlauf: CSV-Download `charts/dlt_DownloadAbfluss.php` (5-Minuten-Werte, für 30 Tage/1 Jahr auf Stunden-/6-Stunden-Höchstwerte ausgedünnt).
- Wasserstand-Verlauf: nur als Grafik (`charts/dlt_wasserstand.php`), der CSV-Export dafür liefert bei vowis keine Daten.
- Für Lorüns-Äule, Lorüns (Restwasser) und Beschling liefert vowis keinen Verlauf.
- Alles Rohdaten ohne Gewähr. Quelle: Land Vorarlberg. Kartenkacheln: © OpenStreetMap-Mitwirkende.

## Nächste Schritte

- Weitere Regionen über den Bundes-Datensatz „Aktuelle Pegelstände Österreich“ (eHYD, CC BY 4.0, nur aktuelle Werte).
