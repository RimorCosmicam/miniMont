# Mont, carried to a desktop

Mont began on a screen three inches across, and every rule in it is a rule about
that screen. Most of them travel. A few of them are rules about a *phone* wearing
the clothes of rules about a *language*, and a desktop is the first place that
difference shows.

This document is the desktop dialect. Nothing here has been promoted into
`Mont/README.md` yet, and nothing should be until it has been seen running on a real
monitor. When you are unsure whether to add something, the answer is no — and that
applies to the language itself more than to anything drawn in it.

## What travels unchanged

The one idea, entirely: everything decoration used to say, the type says instead.

- Selected is simply the bright one. On a desktop that finally earns its keep,
  because a desktop is the first Mont surface with more than one selectable thing on
  it at a time. The focused window, the running app in the dock, the active
  anything — all of it is white at 100% against white at 58%, and none of it is a
  border, a fill, a shadow or an underline.
- Radius 0. Everywhere, still.
- No shadows, no gradients, no blur. 92% black is the entire separation.
- White carries hierarchy through opacity alone, on the existing ladder.
- The type scale, and the weights, and Black as the default rather than an emphasis.
- One accent at a time, carrying a state and never decorating one.
- The stripes, at 26.565°, only on full-screen moments and never behind content —
  with one amendment below.
- No emoji, no pictographic glyphs in chrome.

## What does not travel, and why

### A surface is a card, not a band

Mont says a surface is a full-width black rectangle, and gives the reason: a panel
that stops three quarters of the way across leaves a strip of dead screen beside
every row. On a three-inch display that is exactly right. On a twenty-seven-inch
one, full width is a rectangle a metre long holding four words, and the dead screen
is the rest of the rectangle.

The rule underneath was never really *full width*. It was **a surface goes to the
edge it belongs to**, and on a phone every edge is close enough that they are the
same sentence. On a desktop they are not, so:

> **A Mont surface is sized to its content and floats.** It is still a rectangle,
> still 92% black, still radius 0, still without border or shadow. It simply stops
> where it is finished.

The dock, the status card, the settings card and the launcher are all this.

### The 44dp top inset does not exist here

That inset is a fact about phone hardware — cases lip over the top edge and the OS
reserves pixels there against mis-taps. An external monitor has neither problem. A
rule carried past its reason is superstition, and the desktop's cards sit 14dp off
whichever edge they are near.

### 22 left / 14 right survives text, and only text

The asymmetry exists so text hangs off a generous left margin and nothing needs the
right one. Cards holding rows of words keep it. The dock, which holds a row of icons
and no text at all, has no left margin to be generous with, and is symmetric.

### A card in the middle of a desktop is allowed a header

Mont forbids a panel a header, and gives the reason: a panel that opens on top of
the thing it edits does not need to say which panel it is, and that row was costing
the first line of the list. Both halves of that fail here. A card floating in the
middle of a wallpaper is not visibly about anything, and there is no first line
being crowded out on a screen this size.

So the settings card is allowed the word `SETTINGS` at 16 SemiBold. It is the only
header in the language, and it stays the only one.

## What is genuinely new

Three additions, each of which the language did not previously need. Each is a real
change and is written here to be argued with, not slipped in.

### 1. A hairline on every window

One physical pixel — not one dp, so it stays a hairline at any density — in white at
34%, Mont's existing border alpha, around every window on the desktop.

Mont allows a border on the rare object that needs one, and a window is that object.
Two applications' own artwork can meet at an edge with no contrast between them at
all, and unlike every other object in the language, the desktop does not control the
pixels on either side of that edge. This is the one case where contrast cannot be
arranged and has to be drawn.

It goes on every window rather than only unfocused ones, because a border that
appears and disappears with focus is a border doing a job brightness already does
better, twice.

### 2. Mustard is now close

Mont lists Mustard `#D8A628` as onboarding, a poster colour. In miniMont it is a
square at the left of every title bar, and it closes the window.

Two things about this are worth being honest over. First, it is still one accent at
a time in the sense the rule meant — there is no second accent competing with it in
the chrome; the status card's red is the battery red the language already reserved,
and it appears under 20% and nowhere else. Second, it is the language's first
control that is a **shape rather than a word**, and Mont is otherwise built entirely
out of plain words acting as buttons.

The justification is that a window's close is the one control in a desktop that must
be findable without reading, in the same place, on windows whose contents we do not
control and whose title we might not have room for. A word would be `CLOSE` on every
window in a row of six overlapping windows, and six of the same word is noise where
one coloured square is a landmark.

If that argument does not hold up in use, the fallback is `CLOSE` at the right of the
title bar in Mont Black at 14, and the language goes back to being only words.

### 3. Stripes somebody chose

The wallpaper picker offers the stripes, in mustard, green and red, as a backdrop.
That looks like a straight breach of *never behind content you have to read*, and it
is worth being precise about why it is not.

The rule governs what miniMont does on its own account: the language will not put
ornament behind a working surface. It does not govern what somebody chooses for their
own screen. So the stripes are in the list, they are **still** rather than scrolling,
and miniMont never selects them for you — black is the default and stays it.

### 4. Someone else's icons

The dock holds application icons. Mont's ban is on pictographic glyphs *in the
chrome we draw* — the language will not say a thing with a little picture when a
word will do. An application's icon is not our picture, it is that application's
identity, and it belongs to the same category as the pixels inside its window, which
we also do not redraw.

Brightness still carries state: running at full opacity, pinned-and-idle at 58%,
which is the ordinary rule applied to somebody else's artwork rather than to white.

## What the desktop still owes an answer to

- **The pointer.** Mont has never had a cursor. It needs one, it must be legible over
  a wallpaper and over an app's own white, and the obvious Mont answer — a plain
  white arrow with no outline — is the one that vanishes on a white document. Solve
  it before it becomes a shipped accident.
- **Density.** The scale was drawn for a small screen and is defined in dp, so on a
  desktop it stays physically correct only if the display's density is chosen
  deliberately. 160 was an argument parser's default in AirMate, not a design
  decision. Choose it here.
- **The wallpaper.** Mont has never had one. A picture behind Mont chrome is the
  most un-Mont thing in this document, and it is also what everybody wants from a
  desktop. The cards are 92% black over it, which is the same defence the language
  already trusts over video and over a phone's home screen — but it should be looked
  at over something busy before it is believed.
