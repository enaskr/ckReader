package com.ckchoi.yesss24

/**
 * Page navigation shared by the horizontal spread and vertical webtoon views.
 * A spread requires both dimensions of an unfolded/fold-class display; a
 * folded phone rotated sideways must not accidentally render two pages.
 */
internal object ComicReadingPolicy {
 enum class Direction { HORIZONTAL, VERTICAL }
 fun spread(mode:Direction,enabled:Boolean,widthDp:Int,heightDp:Int):Boolean =
  mode==Direction.HORIZONTAL && enabled && widthDp>=600 && heightDp>=540

 fun first(index:Int,total:Int,spread:Boolean):Int {
  if(total<=0)return 0
  val safe=index.coerceIn(0,total-1)
  return if(spread)safe-safe%2 else safe
 }
 fun visible(index:Int,total:Int,spread:Boolean):List<Int> {
  if(total<=0)return emptyList()
  val start=first(index,total,spread)
  return if(spread && start+1<total)listOf(start,start+1) else listOf(start)
 }
 /** The webtoon viewer retains only three pages, advancing one per scroll. */
 fun verticalWindow(firstVisible:Int,total:Int):List<Int> {
  if(total<=0)return emptyList()
  val first=firstVisible.coerceIn(0,total-1)
  return (first..minOf(total-1,first+2)).toList()
 }
 fun move(index:Int,direction:Int,total:Int,spread:Boolean):Int {
  if(total<=0)return 0
  val step=if(spread)2 else 1
  return first((first(index,total,spread)+step*direction).coerceIn(0,total-1),total,spread)
 }
 fun positionLabel(index:Int,total:Int,spread:Boolean):String {
  if(total<1)return "0 / 0"
  val visible=visible(index,total,spread)
  val start=visible.first()+1
  val end=visible.last()+1
  return if(start==end) start.toString()+" / "+total else start.toString()+"–"+end+" / "+total
 }
}
