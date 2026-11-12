# Lente

App Android di lente d'ingrandimento per persone ipovedenti. Usa la fotocamera come lente,
riconosce i testi e li mostra grandi (o li legge ad alta voce), senza uscire per sbaglio
e senza rovinare lo schermo.

**Scarica l'ultima versione:** [Lente.apk](https://github.com/squaregen/lente/releases/latest/download/Lente.apk)

Per installarla sul telefono: apri il link, scarica il file, toccalo e consenti
"Installa app sconosciute" per il browser quando Android lo chiede.

## Cosa fa

Lo schermo principale è solo l'immagine, con tre piccoli pulsanti rotondi negli angoli
(tenui, per non coprire l'immagine e non rovinare lo schermo):

- **in orizzontale**: **+** in alto a sinistra, **−** in basso a sinistra, **menu** in alto a destra;
- **in verticale**: **−** in basso a sinistra, **+** in basso a destra, **menu** in alto a destra.

| Funzione | Come si usa |
|---|---|
| Zoom | Pulsanti **+** e **−**, pizzicando lo schermo o con i **tasti del volume**. Fino a 30 volte (impostabile fino a 120) |
| Messa a fuoco | Tocca un punto dell'immagine |
| Leggere il testo | **Doppio tocco** sull'immagine: fotografa il testo e lo apre grande nel lettore |
| Menu | Luce, immagine ferma, colori ad alto contrasto, contrasto e luminosità, banda di lettura, blocco del fuoco, blocco nell'app, impostazioni, uscita. Si chiude toccando l'immagine |
| Immagine ferma | Dal menu: poi la ingrandisci e la sposti con il dito (si scambia da sola con una foto ad alta risoluzione, così resta nitida) |
| Modi di lettura | Nel lettore, **Modo** passa tra **Pagina** (testo grande a capo automatico), **Riga** (una riga enorme che scorre: gli occhi stanno fermi), **Parole** (poche parole alla volta, grandi quanto lo schermo) e **Foto** (la foto originale ingrandita che avanza da sola lungo la riga e poi torna a capo) |
| Voce | Spenta: **Ascolta** nel lettore legge il testo solo se lo tocchi |
| Leggere una foto | Da Galleria, WhatsApp o altre app: **Condividi → Leggi con Lente** |
| Riquadro rapido | Aggiungi "Lente" alle impostazioni rapide (tendina) per aprirla con un tocco |

Nessuna scritta compare sopra l'immagine: chi la usa sa già cosa fanno i comandi.

Le dimensioni dell'app non dipendono dalle impostazioni di sistema "dimensione carattere" e
"dimensione schermo" (sul suo telefono sono al massimo e rendevano tutto enorme): pulsanti e
testi hanno la loro misura, regolabile dentro l'app.

## Messa a fuoco intelligente

Oltre all'autofocus continuo del telefono, l'app:

- **rifà la messa a fuoco quando l'immagine diventa sfocata** mentre il telefono è fermo
  (misura la nitidezza di ogni fotogramma);
- **mette a fuoco sul testo** che ha riconosciuto, non solo al centro;
- **mette a fuoco prima di fotografare** il testo da leggere;
- nei modi a due colori **sceglie da sola la soglia tra inchiostro e carta** in base alla luce
  (contrasto automatico), regolabile a mano con Luminosità;
- usa la **stabilizzazione dell'anteprima** quando il telefono la supporta, utile a zoom alti;
- permette di **bloccare il fuoco** (menu), ad esempio per scrivere sotto la fotocamera.

## Lettura

Le visualizzazioni a riga e a parole sono quelle dei videoingranditori da tavolo con sintesi
vocale; la foto guidata riproduce il movimento che si fa a mano con una lente (qualche parola
alla volta da sinistra a destra, poi a capo), ma in automatico e con il resto della pagina
oscurato.

Il riconoscimento è Google ML Kit, sul telefono: veloce (una frazione di secondo), preciso
sul testo stampato, gratuito, funziona senza internet e le foto non escono dal telefono.

## Non si esce per sbaglio

- Schermo intero immersivo: il primo scorrimento dal bordo mostra solo le barre, non esce.
- Il gesto/tasto **Indietro non chiude l'app**: per uscire c'è **menu → Esci dall'app**.
- I bordi in basso sono riservati all'app (dove la mano impugna il telefono) e i tocchi che
  partono dal bordo estremo vengono ignorati.
- **Blocca dentro l'app** (menu, o automatico all'apertura dalle impostazioni) usa il
  "Blocco app" di Android: anche Home e Recenti non fanno uscire.
- In orizzontale si gira solo tra i due lati orizzontali: prendendo in mano il telefono non
  passa mai in verticale per sbaglio.
- Lo schermo resta acceso finché la usi.

## Protezione dello schermo (burn-in)

Una precedente app aveva lasciato le icone impresse sullo schermo OLED. Qui:

- i pulsanti negli angoli sono piccoli e scuri, e **diventano tenui** dopo 8 secondi (regolabile) o appena si tocca l'immagine, restando utilizzabili;
- quando sono visibili **si spostano di qualche pixel ogni 30 secondi** (pixel shift, come i
  televisori OLED);
- i pannelli sono su **sfondo nero** (su OLED il nero è spento e non si consuma);
- i messaggi (zoom, colori) compaiono solo per uno o due secondi;
- se il telefono resta immobile e inutilizzato, lo schermo **si attenua dopo 2 minuti** e può
  **spegnersi dopo 10 minuti** (regolabile); un tocco o un movimento lo riaccendono.

## Standard e pratiche seguite

- **Modalità colore dei videoingranditori** (CCTV): colori naturali, contrastati, grigi, e le
  combinazioni a due colori nero/bianco, bianco/nero, giallo/nero, nero/giallo, giallo/blu,
  bianco/blu, verde/nero, con contrasto e luminosità regolabili; immagine ferma; banda di lettura.
- **WCAG 2.2 / EN 301 549** (normativa europea di accessibilità):
  contrasto dei comandi giallo su nero circa 17:1 (oltre il livello AAA 7:1, criterio 1.4.6);
  pulsanti di almeno 56 dp sull'immagine e 76-80 dp nei menu (oltre 2.5.5 e le linee guida Android di 48 dp);
  testi in sp che seguono la dimensione del carattere di sistema (1.4.4);
  interlinea 1,5 e spaziatura delle lettere nel lettore (1.4.12);
  nessun lampeggiamento (2.3.1); tempo di attenuazione dei pulsanti regolabile o disattivabile (2.2.1);
  ogni comando ha un'etichetta testuale e una descrizione per TalkBack (1.1.1, 4.1.2);
  lo stato è sempre detto in parole ("Sì"/"No", "Luce accesa"), non solo con il colore (1.4.1).
- **Font Atkinson Hyperlegible**, progettato dal Braille Institute per la leggibilità in
  ipovisione (licenza SIL Open Font License, vedi `FONT-LICENSE-OFL.txt`).
- **Riconoscimento del testo sul telefono** (Google ML Kit, modello incluso nell'app): funziona
  senza internet e le immagini non escono dal telefono.
- Idee prese dalle app di riferimento: Lente d'ingrandimento di Apple e Google, Seeing AI,
  Lookout, SuperVision+, e dai videoingranditori da tavolo.

## Compilare

Serve JDK 17 e Android SDK. Da PowerShell:

```powershell
.\build.ps1 -Version 1.0.0 -Code 1
```

L'APK firmato finisce in `dist\`. La chiave di firma sta in `%USERPROFILE%\.lente` (fuori dal repository).
