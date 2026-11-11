# Lente

App Android di lente d'ingrandimento per persone ipovedenti. Usa la fotocamera come lente,
riconosce i testi e li mostra grandi (o li legge ad alta voce), senza uscire per sbaglio
e senza rovinare lo schermo.

**Scarica l'ultima versione:** [Lente.apk](https://github.com/squaregen/lente/releases/latest/download/Lente.apk)

Per installarla sul telefono: apri il link, scarica il file, toccalo e consenti
"Installa app sconosciute" per il browser quando Android lo chiede.

## Cosa fa

| Funzione | Come si usa |
|---|---|
| Zoom | Pizzica lo schermo, usa **Più/Meno** o i **tasti del volume**. Fino a 30 volte (impostabile fino a 120): oltre lo zoom della fotocamera l'immagine viene ingrandita ancora |
| Luce | **Luce** accende la torcia del telefono (lo stato viene ricordato) |
| Colori ad alto contrasto | **Colori** passa tra le modalità; tieni premuto per contrasto e luminosità |
| Immagine ferma | **Ferma** blocca l'immagine: poi la ingrandisci e la sposti con il dito (si scambia da sola con una foto ad alta risoluzione, così resta nitida) |
| Lettura guidata | **Leggi** allarga l'inquadratura e guida a inquadrare tutto il foglio ("Allontana il telefono", "Sposta a sinistra"...), poi scatta da solo quando il telefono è fermo. Tieni premuto **Leggi** per scattare subito |
| Modi di lettura | Nel lettore, **Modo** passa tra **Pagina** (testo grande a capo automatico), **Riga** (una riga enorme che scorre: gli occhi stanno fermi), **Parole** (poche parole alla volta, grandi quanto lo schermo) e **Foto** (la foto originale ingrandita che avanza da sola lungo la riga e poi torna a capo) |
| Voce | Spenta di default, per essere discreti in pubblico: **Ascolta** legge il testo solo se lo tocchi; i suggerimenti a voce si accendono nelle impostazioni. La guida usa messaggi grandi e vibrazioni |
| Proposta automatica | Quando la fotocamera vede un testo compare "Testo trovato: tocca qui per leggerlo grande" |
| Guida di lettura | Una banda orizzontale lascia visibile solo la riga che stai leggendo |
| Leggere una foto | Da Galleria, WhatsApp o altre app: **Condividi → Leggi con Lente** |
| Orizzontale | Si usa in orizzontale (pulsanti in colonna a destra); verticale o automatico nelle impostazioni |
| Riquadro rapido | Aggiungi "Lente" alle impostazioni rapide (tendina) per aprirla con un tocco |

## Messa a fuoco intelligente

Oltre all'autofocus continuo del telefono, l'app:

- **rifà la messa a fuoco quando l'immagine diventa sfocata** mentre il telefono è fermo
  (misura la nitidezza di ogni fotogramma);
- **mette a fuoco sul testo** che ha riconosciuto, non solo al centro;
- **mette a fuoco prima di fotografare** il testo da leggere;
- nei modi a due colori **sceglie da sola la soglia tra inchiostro e carta** in base alla luce
  (contrasto automatico), regolabile a mano con Luminosità;
- **si accorge quando il telefono è troppo vicino**: se l'obiettivo è già alla distanza minima
  e l'immagine resta sfocata, dice "Troppo vicino: allontana il telefono e usa lo zoom"
  (è la causa più comune di immagine sfocata con le lenti digitali);
- usa la **stabilizzazione dell'anteprima** quando il telefono la supporta, utile a zoom alti;
- permette di **bloccare il fuoco** (menu Altro), ad esempio per scrivere sotto la fotocamera.

## Perché la lettura guidata

Chi è ipovedente fatica a capire a che distanza tenere il telefono per prendere tutto il testo.
Le app di riferimento (KNFB Reader, Seeing AI, Lookout) risolvono così: inquadratura larga,
indicazioni su come spostare il telefono, scatto automatico quando il testo è tutto dentro.
Il riconoscimento poi lavora su una foto ad alta risoluzione dell'intero sensore, e la lettura
avviene sullo schermo nel modo preferito, senza dover muovere il telefono lungo le righe.

Le visualizzazioni a riga e a parole sono quelle dei videoingranditori da tavolo con sintesi
vocale; la foto guidata riproduce il movimento che si fa a mano con una lente (qualche parola
alla volta da sinistra a destra, poi a capo), ma in automatico e con il resto della pagina
oscurato.

Il riconoscimento è Google ML Kit, sul telefono: veloce (una frazione di secondo), preciso
sul testo stampato, gratuito, funziona senza internet e le foto non escono dal telefono.

## Non si esce per sbaglio

- Schermo intero immersivo: il primo scorrimento dal bordo mostra solo le barre, non esce.
- Il gesto/tasto **Indietro non chiude l'app**: per uscire c'è **Altro → Esci dall'app**.
- I bordi in basso sono riservati all'app (dove la mano impugna il telefono) e i tocchi che
  partono dal bordo estremo vengono ignorati.
- **Blocca dentro l'app** (menu Altro, o automatico all'apertura dalle impostazioni) usa il
  "Blocco app" di Android: anche Home e Recenti non fanno uscire.
- In orizzontale si gira solo tra i due lati orizzontali: prendendo in mano il telefono non
  passa mai in verticale per sbaglio.
- Lo schermo resta acceso finché la usi.

## Protezione dello schermo (burn-in)

Una precedente app aveva lasciato le icone impresse sullo schermo OLED. Qui:

- i pulsanti **si nascondono da soli** dopo 8 secondi (regolabile); un tocco li fa ricomparire;
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
  pulsanti di almeno 76-80 dp (oltre 2.5.5 e le linee guida Android di 48 dp);
  testi in sp che seguono la dimensione del carattere di sistema (1.4.4);
  interlinea 1,5 e spaziatura delle lettere nel lettore (1.4.12);
  nessun lampeggiamento (2.3.1); tempo di scomparsa dei pulsanti regolabile o disattivabile (2.2.1);
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
