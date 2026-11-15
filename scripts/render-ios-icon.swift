// Build-time asset packaging: center the existing wallet logo on the app background.
import AppKit
import Foundation
let args = CommandLine.arguments
let source = URL(fileURLWithPath: args[1])
let target = URL(fileURLWithPath: args[2])
guard let logo = NSImage(contentsOf: source) else { fatalError("Cannot read wallet logo") }
guard let canvas = CGContext(data: nil, width: 1024, height: 1024, bitsPerComponent: 8,
                             bytesPerRow: 1024 * 4, space: CGColorSpaceCreateDeviceRGB(),
                             bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { fatalError("Cannot allocate icon") }
let context = NSGraphicsContext(cgContext: canvas, flipped: false)
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = context
NSColor(calibratedRed: 16/255, green: 21/255, blue: 23/255, alpha: 1).setFill()
NSRect(x: 0, y: 0, width: 1024, height: 1024).fill()
logo.draw(in: NSRect(x: 62, y: 362, width: 900, height: 300))
NSGraphicsContext.restoreGraphicsState()
guard let image = canvas.makeImage(),
      let png = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:]) else { fatalError("Cannot render icon") }
try png.write(to: target)
