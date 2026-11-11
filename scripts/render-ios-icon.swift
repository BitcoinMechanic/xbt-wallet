// Build-time asset packaging: center the existing wallet logo on the app background.
import AppKit
import Foundation
let args = CommandLine.arguments
let source = URL(fileURLWithPath: args[1])
let target = URL(fileURLWithPath: args[2])
guard let logo = NSImage(contentsOf: source) else { fatalError("Cannot read wallet logo") }
guard let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 1024, pixelsHigh: 1024,
                                    bitsPerSample: 8, samplesPerPixel: 3, hasAlpha: false,
                                    isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
      let context = NSGraphicsContext(bitmapImageRep: bitmap) else { fatalError("Cannot allocate icon") }
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = context
NSColor(calibratedRed: 16/255, green: 21/255, blue: 23/255, alpha: 1).setFill()
NSRect(x: 0, y: 0, width: 1024, height: 1024).fill()
logo.draw(in: NSRect(x: 62, y: 362, width: 900, height: 300))
NSGraphicsContext.restoreGraphicsState()
guard let png = bitmap.representation(using: .png, properties: [:]) else { fatalError("Cannot render icon") }
try png.write(to: target)
