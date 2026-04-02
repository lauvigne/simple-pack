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

L’artefact final macOS est produit sous `build/dist/` :

- `impact-macos-<version>-YYYYMMDD.tar.gz`
