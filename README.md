# Fold Taskbar

A taskbar for the Galaxy Z Fold 8 Ultra's main screen that works with any launcher, including Smart Launcher. One UI switches its own taskbar off when a third-party launcher is the default; this replaces it.

## What it does

- Pinned apps on the left, recent apps on the right, and an all-apps grid. Recent apps keep their place in the bar.
- Tap an app to switch to it. Hold an app to open it in split screen, open it as a pop-up (experimental), pin, unpin or reorder it.
- Shows everywhere on the main screen, home screen included. Hides on the cover screen, the lock screen and the notification shade, and steps aside for the keyboard only if the keyboard would cover it.
- Movable: hold the bar and slide it anywhere. It docks to edges (upright on the sides) and centres on the crease when close to it.
- Collapses to a high-contrast tab: throw it off an edge, or hold the grid button and choose Collapse to a tab. The tab samples the pixels around it and flips its tone to stand out.
- Two shapes (Hinge pair, Capsule), three finishes (Smoked glass, Visconti, Paper), three icon sizes.
- App switching uses Aperture: the app grows from the icon you tapped while a machined double ring opens from it to the screen edges. Switchable to the system animation.
- Always showing by default, or auto-hide: swipe up where the bar sits to call it back. Hold the grid button to switch.

## Get the APK

The **Build APK** workflow runs on every push. Open the latest green run under **Actions**, download **FoldTaskbar-apk**, unzip it on the phone and open the `.apk`. Every build is signed with the same key, so new builds install over the old one.

## First run

1. Open **Fold Taskbar** and tap **Accessibility settings**. Find Fold Taskbar under Installed apps and turn it on.
2. Android blocks accessibility for sideloaded apps at first. If it says the setting is restricted, tap **Allow restricted settings** in the app, then the menu in the top corner of App info, then **Allow restricted settings**. Repeat step 1.

## Why an accessibility service

It is the only way an ordinary app can draw over every other app, know which app is on screen and ask for split screen. The service listens for window changes only. It does not read text or content inside apps.

## Limits

- The bar floats over apps. Unlike Samsung's taskbar, it cannot make apps resize above it, so it covers a strip at the bottom.
- Split screen goes through an invisible full-screen stepping stone, because Android only honours "open beside me" from a full-screen app. If One UI still refuses, the split-screen shortcut is tried. The event log records the outcome.
- Pop-up windows use a mode Android hides from ordinary apps. One UI may open the app full screen instead.
- Full-screen video is not detected yet, so the bar stays over it.
