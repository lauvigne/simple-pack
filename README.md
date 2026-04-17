# Archi Packaging Build

Ce dépôt contient un `build.gradle` unique en Groovy DSL pour packager une distribution Archi multi-OS avec overlays et post-traitements.

Le projet n’utilise pas de Gradle Wrapper. `gradle` doit être installé sur la machine.

## Prérequis

- Java installé et accessible pour Gradle
- Gradle installé sur la machine
- `7zz` installé et accessible, ou chemin fourni via `-PsevenZipBin=...`
- le module SFX Windows présent dans le dépôt : [tools/7z.sfx](/Users/lauvigne/Data/co/Test/gradle/tools/7z.sfx)

Version validée localement :

- Gradle `9.4.1`
- `7zz` Homebrew : `/opt/homebrew/bin/7zz`

## Structure attendue

Le script suppose la présence de ces dossiers d’overlays :

- `addons/common/`
- `addons/linux/`
- `addons/macos/`
- `addons/windows/`

Ils peuvent être absents. Dans ce cas, les tâches `assembleImpact*` passent en `NO-SOURCE`.

## Tâches principales

- `check7zip` : résout le binaire `7zz` ou `7z`
- `packageLinux` : télécharge, extrait, assemble et patche la distribution Linux
- `packageMacos` : télécharge, extrait, assemble et patche la distribution macOS
- `packageWindows` : télécharge, extrait, assemble, patche, archive en `.7z` puis construit l’EXE SFX
- `packageAll` : lance les trois packagings

## Commandes

Vérifier 7-Zip :

```bash
gradle check7zip -PsevenZipBin=/opt/homebrew/bin/7zz
```

Packager Windows :

```bash
gradle packageWindows -PsevenZipBin=/opt/homebrew/bin/7zz
```

Packager tout :

```bash
gradle packageAll -PsevenZipBin=/opt/homebrew/bin/7zz
```

## Propriétés Gradle utiles

- `-PappVersion=5.8.0`
- `-PproductName=Impact`
- `-PsevenZipBin=/chemin/vers/7zz`
- `-PsevenZipSfxModule=/chemin/vers/7z.sfx`
- `-PmacosVariant=silicon`
- `-PmacosVariant=intel`
- `-PlinuxArchiveFile=/chemin/vers/Archi-Linux64-5.8.0.tgz`
- `-PmacosArchiveFile=/chemin/vers/Archi-Mac-Silicon-5.8.0.dmg`
- `-PwindowsArchiveFile=/chemin/vers/Archi-Win64-5.8.0.zip`

## Sorties

Les répertoires de travail sont créés sous `build/impact-linux`, `build/impact-macos` et `build/impact-windows`.

Les artefacts finaux Windows sont produits sous `build/dist/` :

- `impact-windows.7z`
- `impact-windows.sfx.txt`
- `impact-windows-YYYYMMDD.exe`
- `impact-windows-YYYYMMDD.zip`

L’artefact final macOS est produit sous `build/dist/` :

- `impact-macos-<version>-YYYYMMDD.tar.gz`

## Docker Linux Headless

Un [Dockerfile] est fourni pour exécuter la distribution Linux d’Archi en environnement headless avec `xvfb`.

Il se base sur `debian:bookworm-slim` et cible en priorité le mode CLI d’Archi pour lancer des scripts jArchi sur un modèle.

Préparation :

```bash
gradle packageLinux -PsevenZipBin=/chemin/vers/7zz
docker build -t archi-headless .
```

Le `Dockerfile` attend une distribution Linux déjà préparée dans `build/impact-linux/Archi/`.

Exécution :

```bash
docker run --rm archi-headless
```

Le comportement par défaut affiche l’aide du CLI Archi.

Pour charger un modèle et exécuter un script jArchi :

```bash
docker run --rm archi-headless \
  bash -lc 'xvfb-run -a /opt/archi/Archi \
    -application com.archimatetool.commandline.app \
    -consoleLog \
    -nosplash \
    --loadModel /work/model.archimate \
    --script.runScript /work/script.ajs \
    --saveModel /work/model.archimate'
```

Il faut alors monter les fichiers dans le conteneur, par exemple :

```bash
docker run --rm \
  -v "$PWD/tests:/work" \
  archi-headless \
  bash -lc 'xvfb-run -a /opt/archi/Archi \
    -application com.archimatetool.commandline.app \
    -consoleLog \
    -nosplash \
    --loadModel /work/model.archimate \
    --script.runScript /work/script.ajs \
    --saveModel /work/model.archimate'
```
