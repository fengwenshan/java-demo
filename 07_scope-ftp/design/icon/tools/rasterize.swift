import AppKit

// 用法: rasterize <in.svg> <out.png> <size>
// 用 AppKit 的 CoreSVG 把 SVG 按目标尺寸渲染成透明 PNG
guard CommandLine.arguments.count >= 4 else {
    FileHandle.standardError.write("usage: rasterize <in.svg> <out.png> <size>\n".data(using: .utf8)!)
    exit(1)
}
let inPath = CommandLine.arguments[1]
let outPath = CommandLine.arguments[2]
let size = Int(CommandLine.arguments[3]) ?? 1024

guard let img = NSImage(contentsOfFile: inPath) else {
    FileHandle.standardError.write("cannot load svg: \(inPath)\n".data(using: .utf8)!)
    exit(2)
}
guard let rep = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
) else { exit(3) }
rep.size = NSSize(width: size, height: size)

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSGraphicsContext.current?.imageInterpolation = .high
NSColor.clear.set()
NSRect(x: 0, y: 0, width: size, height: size).fill(using: .copy)
img.draw(in: NSRect(x: 0, y: 0, width: size, height: size),
         from: NSRect(origin: .zero, size: img.size),
         operation: .sourceOver, fraction: 1.0)
NSGraphicsContext.restoreGraphicsState()

guard let png = rep.representation(using: .png, properties: [:]) else { exit(4) }
try png.write(to: URL(fileURLWithPath: outPath))
print("ok \(outPath) \(size)x\(size)")
