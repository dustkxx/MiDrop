import AppKit
let root=URL(fileURLWithPath:CommandLine.arguments[1])
let w=1600,h=1160
let rep=NSBitmapImageRep(bitmapDataPlanes:nil,pixelsWide:w,pixelsHigh:h,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:0,bitsPerPixel:0)!
NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current=NSGraphicsContext(bitmapImageRep:rep)
NSColor(calibratedWhite:0.955,alpha:1).setFill(); NSBezierPath(rect:NSRect(x:0,y:0,width:w,height:h)).fill()
func label(_ s:String,x:CGFloat,top:CGFloat,size:CGFloat,bold:Bool=false,color:NSColor = .labelColor) {
    (s as NSString).draw(at:NSPoint(x:x,y:CGFloat(h)-top-size*1.3),withAttributes:[.font:NSFont.systemFont(ofSize:size,weight:bold ? .semibold:.regular),.foregroundColor:color])
}
func image(_ name:String,x:CGFloat,top:CGFloat,width:CGFloat,height:CGFloat,radius:CGFloat) {
    guard let image=NSImage(contentsOf:root.appendingPathComponent(name)) else { return }
    let rect=NSRect(x:x,y:CGFloat(h)-top-height,width:width,height:height)
    NSGraphicsContext.saveGraphicsState(); NSBezierPath(roundedRect:rect,xRadius:radius,yRadius:radius).addClip()
    image.draw(in:rect,from:.zero,operation:.sourceOver,fraction:1)
    NSGraphicsContext.restoreGraphicsState()
    NSColor.black.withAlphaComponent(0.06).setStroke(); let path=NSBezierPath(roundedRect:rect,xRadius:radius,yRadius:radius); path.lineWidth=1;path.stroke()
}
image("assets/MiDrop-1024.png",x:60,top:48,width:76,height:76,radius:0)
label("MiDrop 0.2",x:152,top:48,size:34,bold:true)
label("轻松传递，在你的设备之间。",x:154,top:98,size:16,color:.secondaryLabelColor)
label("MAC · LIGHT",x:72,top:181,size:13,bold:true,color:.secondaryLabelColor)
label("MAC · DARK",x:536,top:181,size:13,bold:true,color:.secondaryLabelColor)
label("ANDROID · LIGHT",x:1050,top:181,size:13,bold:true,color:.secondaryLabelColor)
image("previews/mac-light.png",x:72,top:218,width:414,height:552,radius:19)
image("previews/mac-dark.png",x:536,top:218,width:414,height:552,radius:19)
image("previews/android-light.png",x:1050,top:218,width:360,height:800,radius:25)
label("首次允许后自动连接",x:74,top:847,size:20,bold:true)
label("双向发送 · 手机逐批确认 · 局域网加密",x:74,top:890,size:17,color:.secondaryLabelColor)
label("Mac 为原生界面演示数据渲染；Android 为实际 APK 在 Android 16 测试环境的截图。",x:74,top:1080,size:13,color:.secondaryLabelColor)
NSGraphicsContext.restoreGraphicsState()
try rep.representation(using:.png,properties:[:])!.write(to:root.appendingPathComponent("previews/overview.png"))
