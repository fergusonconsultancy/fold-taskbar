# Fold Taskbar

A taskbar for the Galaxy Z Fold 8 Ultra's main screen that works with any launcher, including Smart Launcher. One UI switches its own taskbar off when a third-party launcher is the default; this replaces it.

## What it does

- Pinned apps on the left, recent apps on the right, and an all-apps grid. Recent apps keep their place in the bar.
- Tap an app to switch to it. Hold an app to open it in split screen, open it as a pop-up (experimental), pin, unpin or reorder it.
- Shows everywhere on the main screen, home screen included. Hides on the cover screen, the lock screen and the notification shade, and steps aside for the keyboard only if the keyboard would cover it.
- Movable: hold the grid button (the bar's handle) and slide it anywhere. It docks to edges (upright on the sides) and centres on the crease when close to it.
- Collapses to a high-contrast tab: throw it off an edge, or hold the grid button and choose Collapse to a tab. The tab samples the pixels around it and flips its tone to stand out.
- Icons come from your icon pack (Icon Pack Studio by default when installed, or any standard pack), so the bar and grid match your home screen. Apps the pack does not cover get its background and mask.
- The grid pours up out of the grid button like honey and its icons bubble up on soft springs; opening an app drains the grid into its icon. The active-app light travels as a liquid, thinning as it stretches. Taps ripple through neighbouring icons.
- Two shapes (Hinge pair, Capsule), three finishes, three icon sizes. Smoked glass is adaptive zone by zone: each of eight stretches of the bar reads what is behind it and turns dark over light content, light over dark, with a trace of the colour underneath, blended into one continuous piece of glass.
- App switching: Zoom (the app's last screen grows out of its icon and dissolves into the live app), Reveal, Grow or the system animation. A refused animation never stops an app opening.
- Drag any app out of the bar (press and slide) or the grid (hold, then slide) for a true One UI split screen. The bar hands Android an app drag, the same route Samsung's own taskbar uses, so One UI's own drop zones, app picker and divider take over.
- Vanishes the instant the phone starts to fold (hinge sensor), so it never lingers on the cover screen.
- App pairs: hold an app and choose Pair with another app, or hold the grid button to save the two apps on screen as a pair. Pairs sit in the bar: drag a pair to either half and the first app opens while the second lands in One UI's split, in one movement. Tapping a pair opens the first app and points you to the drag.
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
- True split screen only comes from a drag: One UI refused every other route in testing (its split shortcut, a fresh window, a relaunch). So the hold menu's split option and a tapped pair show where to drag instead of opening a lesser split.
- Flex mode (phone half open) hides the bar, because the hinge reads as closing.
- Pop-up windows use a mode Android hides from ordinary apps. One UI may open the app full screen instead.
- Full-screen video is not detected yet, so the bar stays over it.
