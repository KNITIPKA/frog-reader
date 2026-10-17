# Animated segmented controls

`ui/components/FrogSegmentedControl.kt` owns the sliding capsule used by reader
choice rows, Settings controls, the welcome screen's startup choice, Quotes /
Bookmarks tabs, and the Library grid/list control. Callers retain their existing
palettes, labels, settings ownership and callbacks.

The main Library / Profile dock uses `PagerPillInteraction.kt` with the same grab
geometry and release decisions. Its normal motion continues to follow the main
pager directly. Grabbing the visible pill temporarily previews its position;
only release requests a page. A settling spring then hands control back once the
pager reaches that destination, avoiding a jump back to the old page. Icons and
labels follow the same visible progress, including in RTL. Accessibility tab
selection continues to follow the settled page.

Tapping a choice moves the capsule with a damped spring. Both position and width
animate, including unequal-width options. Selected foreground ink is clipped to
the moving capsule so the text remains readable between two choices. The initial
selection is drawn on the first measured frame. Compose's motion duration scale
continues to apply to settling animations.

A drag must start on the visible capsule and cross horizontal touch slop. The
point grabbed on it is preserved; its width interpolates as it moves between
options. Values are previewed while dragging and committed once on release.
The greatest capsule overlap determines the destination; distance to segment
centers and finally finger position resolve ties. End positions are clamped.
Physical bounds share one pixel-rounding calculation for layout, drawing and RTL.

Vertical intent before a horizontal grab belongs to the surrounding scroll view.
Consumed gestures, an additional finger, disabling, layout changes or an external
selection change cancel the grab. A cancelled grab settles back without invoking
the owner's selection callback. Normal item taps and reselecting an item still
reach the existing owner (for example, reopening the custom-font picker).

The selected accessibility state follows the committed value. The extra clipped
foreground layer has no semantics or input handlers; it does not duplicate
TalkBack targets. The independent reading-page colors and all app palettes remain
owned by their existing theme sources.

`SegmentedControlGeometryTest` covers grip preservation, overlap/tie decisions,
unequal widths, RTL, bounds, release-only commitment and gesture cancellation.
Native preview fixtures cover fixed themes, Material You, large text and actual
reader, welcome, saved-passage and library contexts. Native stills and JVM tests
do not establish physical-device gesture feel, animation smoothness or TalkBack
behavior; those require runtime checks.
