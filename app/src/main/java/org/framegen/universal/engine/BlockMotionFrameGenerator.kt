package org.framegen.universal.engine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** CPU reference engine and fallback. */
class BlockMotionFrameGenerator(
    private val downsample: Int = 4,
    private val blockSize: Int = 8,
    private val searchRadius: Int = 4,
    private val sampleStep: Int = 2
) : FrameInterpolationEngine {
    override val engineId = "cpu-block-motion"
    override val displayName = "CPU Block Motion"
    init {
        require(downsample in 1..16)
        require(blockSize in 2..32)
        require(searchRadius in 0..12)
        require(sampleStep in 1..8)
    }
    private data class Flow(val cols:Int,val rows:Int,val dx:IntArray,val dy:IntArray,val error:IntArray)

    override fun interpolate(previous:IntArray,current:IntArray,width:Int,height:Int):GeneratedFrame? {
        if(width<32 || height<32) return null
        val count=width.toLong()*height.toLong()
        if(count>MAX_PIXELS || count>Int.MAX_VALUE || previous.size!=count.toInt() || current.size!=count.toInt()) return null
        val start=System.nanoTime()
        val sw=((width.toLong()+downsample-1)/downsample).toInt()
        val sh=((height.toLong()+downsample-1)/downsample).toInt()
        val a=luma(previous,width,height,sw,sh); val b=luma(current,width,height,sw,sh)
        val f=estimate(a,b,sw,sh); val back=estimate(b,a,sw,sh)
        val out=IntArray(count.toInt())
        for(y in 0 until height) {
            val sy=y/downsample; val cy=(sy/blockSize).coerceIn(0,f.rows-1); val row=y*width
            for(x in 0 until width) {
                val sx=x/downsample; val cx=(sx/blockSize).coerceIn(0,f.cols-1); val cell=cy*f.cols+cx
                val dxs=f.dx[cell]; val dys=f.dy[cell]
                val dx=dxs*downsample.toFloat(); val dy=dys*downsample.toFloat()
                val tx=(sx+dxs).coerceIn(0,sw-1); val ty=(sy+dys).coerceIn(0,sh-1)
                val bx=(tx/blockSize).coerceIn(0,back.cols-1); val by=(ty/blockSize).coerceIn(0,back.rows-1)
                val bi=by*back.cols+bx
                val ok=abs(dxs+back.dx[bi])<=1 && abs(dys+back.dy[bi])<=1
                val conf=when { !ok || f.error[cell]>28 -> 0f; f.error[cell]>14 -> (28-f.error[cell])/14f; else -> 1f }
                val wa=sample(previous,width,height,x-dx*.5f,y-dy*.5f)
                val wb=sample(current,width,height,x+dx*.5f,y+dy*.5f)
                out[row+x]=mix(mix(previous[row+x],current[row+x],.5f),mix(wa,wb,.5f),conf)
            }
        }
        return GeneratedFrame(out,width,height,(System.nanoTime()-start)/1_000_000L)
    }

    private fun luma(p:IntArray,w:Int,h:Int,ow:Int,oh:Int):ByteArray {
        val out=ByteArray(ow*oh)
        for(y in 0 until oh) for(x in 0 until ow) {
            val c=p[min(h-1,y*downsample)*w+min(w-1,x*downsample)]
            val r=(c ushr 16) and 255; val g=(c ushr 8) and 255; val b=c and 255
            out[y*ow+x]=((77*r+150*g+29*b) shr 8).toByte()
        }
        return out
    }
    private fun estimate(a:ByteArray,b:ByteArray,w:Int,h:Int):Flow {
        val cols=max(1,(w+blockSize-1)/blockSize); val rows=max(1,(h+blockSize-1)/blockSize)
        val dx=IntArray(cols*rows); val dy=IntArray(cols*rows); val err=IntArray(cols*rows)
        for(gy in 0 until rows) for(gx in 0 until cols) {
            val cx=min(w-1,gx*blockSize+blockSize/2); val cy=min(h-1,gy*blockSize+blockSize/2)
            var best=Int.MAX_VALUE; var bestX=0; var bestY=0
            for(oy in -searchRadius..searchRadius) for(ox in -searchRadius..searchRadius) {
                var cost=0; var samples=0; var py=-blockSize/2
                while(py<blockSize/2) {
                    val ay=cy+py; val by=ay+oy
                    if(ay in 0 until h && by in 0 until h) {
                        var px=-blockSize/2
                        while(px<blockSize/2) {
                            val ax=cx+px; val bx=ax+ox
                            if(ax in 0 until w && bx in 0 until w) {
                                cost+=abs((a[ay*w+ax].toInt() and 255)-(b[by*w+bx].toInt() and 255)); samples++
                            }
                            px+=sampleStep
                        }
                    }
                    py+=sampleStep
                }
                val score=if(samples==0) Int.MAX_VALUE else cost/samples
                val dist=ox*ox+oy*oy; val bestDist=bestX*bestX+bestY*bestY
                if(score<best || (score==best && dist<bestDist)) { best=score; bestX=ox; bestY=oy }
            }
            val i=gy*cols+gx; dx[i]=bestX; dy[i]=bestY; err[i]=if(best==Int.MAX_VALUE)255 else best
        }
        return Flow(cols,rows,dx,dy,err)
    }
    private fun sample(p:IntArray,w:Int,h:Int,x:Float,y:Float):Int {
        val fx=x.coerceIn(0f,(w-1).toFloat()); val fy=y.coerceIn(0f,(h-1).toFloat())
        val x0=fx.toInt(); val y0=fy.toInt(); val x1=min(w-1,x0+1); val y1=min(h-1,y0+1)
        val tx=fx-x0; val ty=fy-y0
        fun ch(s:Int):Int {
            val a=(p[y0*w+x0] ushr s) and 255; val b=(p[y0*w+x1] ushr s) and 255
            val c=(p[y1*w+x0] ushr s) and 255; val d=(p[y1*w+x1] ushr s) and 255
            val top=a+(b-a)*tx; val bottom=c+(d-c)*tx
            return (top+(bottom-top)*ty).toInt().coerceIn(0,255)
        }
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    private fun mix(a:Int,b:Int,t:Float):Int {
        fun ch(s:Int):Int { val x=(a ushr s) and 255; val y=(b ushr s) and 255; return (x+((y-x)*t).toInt()).coerceIn(0,255) }
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    private companion object { const val MAX_PIXELS=4_194_304L }
}
