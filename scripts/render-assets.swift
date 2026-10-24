import AppKit
let root=URL(fileURLWithPath:CommandLine.arguments[1])
let size=1024
let rep=NSBitmapImageRep(bitmapDataPlanes:nil,pixelsWide:size,pixelsHigh:size,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:0,bitsPerPixel:0)!
NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current=NSGraphicsContext(bitmapImageRep:rep)
let c=NSGraphicsContext.current!.cgContext
c.translateBy(x:0,y:1024); c.scaleBy(x:1,y:-1)
c.setFillColor(NSColor(red:0.07,green:0.13,blue:0.105,alpha:1).cgColor)
c.addPath(CGPath(roundedRect:CGRect(x:44,y:44,width:936,height:936),cornerWidth:210,cornerHeight:210,transform:nil)); c.fillPath()
c.setStrokeColor(NSColor.white.withAlphaComponent(0.12).cgColor); c.setLineWidth(2)
c.addPath(CGPath(roundedRect:CGRect(x:45,y:45,width:934,height:934),cornerWidth:209,cornerHeight:209,transform:nil)); c.strokePath()
c.translateBy(x:40,y:40); c.scaleBy(x:0.92,y:0.92); c.setLineWidth(112); c.setLineCap(.round)
let a=CGMutablePath(); a.move(to:CGPoint(x:608,y:276)); a.addCurve(to:CGPoint(x:292,y:380),control1:CGPoint(x:492,y:192),control2:CGPoint(x:330,y:244)); a.addCurve(to:CGPoint(x:416,y:628),control1:CGPoint(x:262,y:488),control2:CGPoint(x:316,y:588))
c.addPath(a); c.setStrokeColor(NSColor(red:0.58,green:0.90,blue:0.76,alpha:1).cgColor); c.strokePath()
let b=CGMutablePath(); b.move(to:CGPoint(x:416,y:748)); b.addCurve(to:CGPoint(x:732,y:644),control1:CGPoint(x:532,y:832),control2:CGPoint(x:694,y:780)); b.addCurve(to:CGPoint(x:608,y:396),control1:CGPoint(x:762,y:536),control2:CGPoint(x:708,y:436))
c.addPath(b); c.setStrokeColor(NSColor(red:0.91,green:0.95,blue:0.92,alpha:1).cgColor); c.strokePath()
NSGraphicsContext.restoreGraphicsState()
try rep.representation(using:.png,properties:[:])!.write(to:root.appendingPathComponent("assets/MiDrop-1024.png"))
