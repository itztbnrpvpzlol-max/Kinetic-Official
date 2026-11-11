# Kinetic icon and typography assets

## Icons
- `kinetic-icons.svg` is the editable SVG source sprite for the editor's core controls.
- The runtime icon renderer is `com.kinetic.app.ui.KIcon` in Compose. It uses matching native vector paths so frequently redrawn timeline controls do not require SVG rasterization.
- Icon style: 24-unit grid, rounded joins/caps, 1.8-unit outline, monochrome tint supplied by the component. Use the accent color for selected/active states rather than baking color into the artwork.
- SVG source is original Kinetic artwork, informed by common editor metaphors. Keep new icons on the same grid and stroke system.

## Typography
- `KineticFonts` defines the app-wide family and weight tokens.
- The app uses Android's native sans-serif family (Roboto on current Android releases) with explicit Regular, Medium, SemiBold, and Bold weights. This is a high-quality, offline, platform-native alternative to Apple's San Francisco; Apple's font is not redistributed in this Android app.
- Typography roles are centralized in `KineticTypography` in `Theme.kt`.
