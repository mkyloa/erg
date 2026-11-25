// Renders Erg brand assets (icon, banner, logo).
// Usage: swift tools/brand/render.swift app/src/main/res assets   (or "preview" to write previews to .)
import AppKit
import CoreText

let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "."
// Icon background: black, for a pure black-and-white mark
let navy = CGColor(red: 0, green: 0, blue: 0, alpha: 1)
let white = CGColor(red: 1, green: 1, blue: 1, alpha: 1)

// The mark: two sand ridges, in a 108x108 adaptive-icon viewport (y down).
// Keep in sync with app_icon_foreground.xml.
func ridgeBack() -> CGPath {
	// Crescent dune, tapering to points at both ends
	let p = CGMutablePath()
	p.move(to: CGPoint(x: 22, y: 61))
	p.addCurve(to: CGPoint(x: 62, y: 41), control1: CGPoint(x: 34, y: 47), control2: CGPoint(x: 50, y: 37))
	p.addCurve(to: CGPoint(x: 86, y: 58), control1: CGPoint(x: 72, y: 45), control2: CGPoint(x: 78, y: 54))
	p.addCurve(to: CGPoint(x: 22, y: 61), control1: CGPoint(x: 66, y: 53), control2: CGPoint(x: 42, y: 55))
	p.closeSubpath()
	return p
}
func ridgeFront() -> CGPath {
	let p = CGMutablePath()
	p.move(to: CGPoint(x: 21, y: 72))
	p.addCurve(to: CGPoint(x: 58, y: 55), control1: CGPoint(x: 32, y: 61), control2: CGPoint(x: 46, y: 53))
	p.addCurve(to: CGPoint(x: 87, y: 71), control1: CGPoint(x: 70, y: 57), control2: CGPoint(x: 80, y: 65))
	p.addCurve(to: CGPoint(x: 21, y: 72), control1: CGPoint(x: 66, y: 66), control2: CGPoint(x: 40, y: 66))
	p.closeSubpath()
	return p
}

func makeContext(_ w: Int, _ h: Int) -> CGContext {
	let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
		space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
	// Flip to y-down so path coordinates match Android's
	ctx.translateBy(x: 0, y: CGFloat(h)); ctx.scaleBy(x: 1, y: -1)
	return ctx
}

func save(_ ctx: CGContext, _ name: String) {
	let img = ctx.makeImage()!
	let rep = NSBitmapImageRep(cgImage: img)
	let data = rep.representation(using: .png, properties: [:])!
	let url = URL(fileURLWithPath: out).appendingPathComponent(name)
	try! FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
	try! data.write(to: url)
	print("wrote \(name) \(ctx.width)x\(ctx.height)")
}

/// Square launcher icon: navy, rounded, with the mark scaled into it.
func icon(_ px: Int, _ name: String, rounded: Bool = true) {
	let ctx = makeContext(px, px)
	let s = CGFloat(px)
	if rounded {
		ctx.addPath(CGPath(roundedRect: CGRect(x: 0, y: 0, width: s, height: s), cornerWidth: s * 0.18, cornerHeight: s * 0.18, transform: nil))
		ctx.clip()
	}
	ctx.setFillColor(navy); ctx.fill(CGRect(x: 0, y: 0, width: s, height: s))
	// Map the 108 viewport onto the icon, zooming in on the 66dp safe zone
	let scale = s / 76.0
	ctx.translateBy(x: -16 * scale, y: -16 * scale)
	ctx.scaleBy(x: scale, y: scale)
	ctx.setFillColor(white.copy(alpha: 0.45)!); ctx.addPath(ridgeBack()); ctx.fillPath()
	ctx.setFillColor(white); ctx.addPath(ridgeFront()); ctx.fillPath()
	save(ctx, name)
}

/// Draws "ERG" with wide tracking, centered at (cx, cy), cap height ≈ height.
func wordmark(_ ctx: CGContext, cx: CGFloat, cy: CGFloat, capHeight: CGFloat, color: CGColor) {
	let fontPath = "/System/Library/Fonts/Supplemental/DIN Condensed Bold.ttf"
	let desc = CTFontManagerCreateFontDescriptorsFromURL(URL(fileURLWithPath: fontPath) as CFURL) as! [CTFontDescriptor]
	let probe = CTFontCreateWithFontDescriptor(desc[0], 100, nil)
	let size = 100 * capHeight / CTFontGetCapHeight(probe)
	let font = CTFontCreateWithFontDescriptor(desc[0], size, nil)
	let attrs: [NSAttributedString.Key: Any] = [
		NSAttributedString.Key(kCTFontAttributeName as String): font,
		NSAttributedString.Key(kCTForegroundColorAttributeName as String): color,
		NSAttributedString.Key(kCTKernAttributeName as String): size * 0.12,
	]
	let line = CTLineCreateWithAttributedString(NSAttributedString(string: "ERG", attributes: attrs))
	let bounds = CTLineGetImageBounds(line, ctx)
	ctx.saveGState()
	// Text draws y-up: undo the flip locally
	ctx.translateBy(x: 0, y: CGFloat(ctx.height)); ctx.scaleBy(x: 1, y: -1)
	let yUp = CGFloat(ctx.height) - cy
	ctx.textPosition = CGPoint(x: cx - bounds.width / 2 - bounds.minX, y: yUp - bounds.height / 2 - bounds.minY)
	CTLineDraw(line, ctx)
	ctx.restoreGState()
}

/// TV launcher banner (16:9): the white wordmark on a transparent background, so it sits on the
/// launcher's wallpaper like icon-pack banners (Projectivy + Projectify) instead of a black box.
/// Cap height matches DUNE's banner in those packs.
func banner(_ w: Int, _ name: String) {
	let h = w * 9 / 16
	let ctx = makeContext(w, h)
	wordmark(ctx, cx: CGFloat(w) / 2, cy: CGFloat(h) / 2, capHeight: CGFloat(h) * 0.40, color: white)
	save(ctx, name)
}

/// Transparent splash/toolbar logo, same canvas as DUNE's (3:2).
func logo(_ w: Int, _ name: String) {
	let h = w * 2 / 3
	let ctx = makeContext(w, h)
	wordmark(ctx, cx: CGFloat(w) / 2, cy: CGFloat(h) / 2, capHeight: CGFloat(h) * 0.16, color: white)
	save(ctx, name)
}

let mode = CommandLine.arguments.count > 2 ? CommandLine.arguments[2] : "preview"
if mode == "preview" {
	icon(512, "preview_icon.png")
	banner(960, "preview_banner.png")
	logo(1350, "preview_logo.png")
} else {
	for (dpi, iconPx, bannerW) in [("mdpi", 80, 160), ("hdpi", 120, 240), ("xhdpi", 160, 320), ("xxhdpi", 240, 480), ("xxxhdpi", 320, 640)] {
		icon(iconPx, "mipmap-\(dpi)/app_icon.png", rounded: false)
		banner(bannerW, "mipmap-\(dpi)/app_banner.png")
	}
	banner(320, "drawable/app_banner.png")
	logo(1350, "drawable/app_logo.png")
}
