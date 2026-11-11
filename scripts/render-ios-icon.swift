// Build-time asset packaging: center the existing wallet logo on the app background.
import AppKit
import Foundation
let args = CommandLine.arguments
let source = URL(fileURLWithPath: args[1])
let target = URL(fileURLWithPath: args[2])
guard let logo = NSImage(contentsOf: source) else { fatalError("Cannot read wallet logo") }
let image = NSImage(size: NSSize(width: 1024, height: 1024))
image.lockFocus()
NSColor(calibratedRed: 16/255, green: 21/255, blue: 23/255, alpha: 1).setFill()
NSRect(x: 0, y: 0, width: 1024, height: 1024).fill()
logo.draw(in: NSRect(x: 62, y: 362, width: 900, height: 300))
image.unlockFocus()
guard let tiff = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff),
      let png = bitmap.representation(using: .png, properties: [:]) else { fatalError("Cannot render icon") }
try png.write(to: target)
