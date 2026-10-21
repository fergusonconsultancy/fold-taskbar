# Fold Taskbar

A taskbar for the Galaxy Z Fold 8 Ultra's main screen that works with any launcher, including Smart Launcher. One UI switches its own taskbar off when a third-party launcher is the default; this replaces it.

## What it does

- Pinned apps on the left, recent apps on the right, and an all-apps grid. Recent apps keep their place in the bar.
- Tap an app to switch to it. Hold an app to open it in split screen, open it as a pop-up (experimental), pin, unpin or reorder it.
- Shows only on the main screen. Hides on the cover screen, the home screen, the lock screen, the notification shade and while the keyboard is up.
- Two shapes (Hinge pair, Capsule), three finishes (Smoked glass, Visconti, Paper), three icon sizes.
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
- Split screen tries a direct launch first, then falls back to Android's split-screen shortcut. The event log on the setup screen records which one One UI accepted.
- Pop-up windows use a mode Android hides from ordinary apps. One UI may open the app full screen instead.
- Full-screen video is not detected yet, so the bar stays over it.
