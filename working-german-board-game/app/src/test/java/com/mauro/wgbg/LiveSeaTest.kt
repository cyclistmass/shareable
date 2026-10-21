package com.mauro.wgbg

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import wgbg.engine.*
import kotlin.math.*

class LiveSeaTest {
    private val board = Rules.newGame(GameConfig(), 81).board
    private fun close(a: Offset, b: Offset) {
        assertEquals(a.x, b.x, .00001f); assertEquals(a.y, b.y, .00001f)
    }

    @Test fun `coast contains all and only the thirty boundary edges with no disconnected ends`() {
        val coast = liveCoast(board)
        assertEquals(30, coast.size)
        assertEquals(30, coast.map { it.edge }.toSet().size)
        val endpoints = coast.flatMap { listOf(it.a,it.b) }.groupingBy { it }.eachCount()
        assertEquals(30,endpoints.size); assertTrue(endpoints.values.all { it == 2 })
        for (edge in coast) {
            assertEquals(1f, (edge.b-edge.a).getDistance(), .00001f)
            assertEquals(1f, edge.outward.getDistance(), .00001f)
            assertEquals(0f, (edge.b-edge.a).let { it.x*edge.outward.x+it.y*edge.outward.y }, .00001f)
            assertTrue(edge.center.x*edge.outward.x+edge.center.y*edge.outward.y > 0)
            val p=liveCoastPlacements(board).single { it.position==edge.center }
            val m=liveModelMatrix(p)
            close(edge.outward,Offset(m[8],m[10]))
        }
    }

    @Test fun `live water taps invert the sea height under every tilt and twist`() {
        val point=Offset(4.7f,1.3f)
        for (tilt in listOf(0f,.48f,.96f)) for (yaw in 0..15) {
            val p=LiveProjection(Offset(800f,400f),160f,tilt,yaw*PI.toFloat()/8)
            close(point,p.unproject(p.project(point,LIVE_WATER_HEIGHT),LIVE_WATER_HEIGHT))
        }
    }

    @Test fun `boat and bird models follow simulation poses and density without advancing or mutating it`() {
        val sea=Sea(board,55);sea.update(.05f)
        val time=sea.time;val cloud=sea.clouds.first();val cloudAt=Offset(cloud.x,cloud.y)
        val poses=liveSeaPlacements(sea,false)
        assertEquals(sea.boatCount,poses.count { it.model=="live_sloop" })
        assertEquals(sea.birdCount,poses.count { it.model.startsWith("live_gull_") })
        for ((i,pose) in poses.filter { it.model=="live_sloop" }.withIndex()) {
            val boat=sea.boats[i];close(Offset(boat.x,boat.y),pose.position)
            val m=liveModelMatrix(pose)
            close(Offset(cos(boat.heading),sin(boat.heading)),Offset(m[0],m[2]))
            assertEquals(boat.color,pose.owner)
        }
        assertEquals(time,sea.time);close(cloudAt,Offset(cloud.x,cloud.y))
        sea.density01=0f;assertTrue(liveSeaPlacements(sea,false).isEmpty())
    }

    @Test fun `shared cloud gestures still move fling and poof the object drawn in live view`() {
        val sea=Sea(board,7);val cloud=sea.clouds.first();val at=Offset(cloud.x,cloud.y)
        sea.move(cloud,at);assertTrue(cloud.dragging)
        sea.move(cloud,at+Offset(.3f,.2f));sea.update(.05f);sea.release(cloud)
        sea.fling(cloud,2f,-1f);assertTrue(cloud.vx>0);assertFalse(cloud.dragging)
        repeat(Sea.POKES_TO_POOF) { sea.poof(cloud,Offset(cloud.x,cloud.y)) }
        assertTrue(cloud.poof>=0)
    }

    @Test fun `invalid mapping inputs are rejected without changing the board`() {
        for (bad in listOf(Float.NaN,Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { liveSeaHeading(bad) }
            assertThrows(IllegalArgumentException::class.java) { LivePlacement("live_sloop",Offset(bad,0f)) }
            assertThrows(IllegalArgumentException::class.java) { LivePlacement("sea_wake",Offset.Zero,alpha=bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { LivePlacement("sea_wake",Offset.Zero,alpha=-.1f) }
    }

    @Test fun `signed coast distances locate the foot of every wall and distinguish the island`() {
        val coast=liveCoast(board)
        assertTrue(liveCoastDistance(coast,Offset.Zero)<0)
        assertTrue(liveCoastDistance(coast,Offset(10f,3f))>0)
        for (edge in coast) {
            assertEquals(0f,liveCoastDistance(coast,edge.center),.00001f)
            assertEquals(LIVE_COAST_FOOT,liveCoastDistance(coast,edge.center+edge.outward*LIVE_COAST_FOOT),.00001f)
            assertTrue(liveCoastDistance(coast,edge.center-edge.outward*.1f)<0)
        }
    }

    @Test fun `coast texture encoding agrees with exact geometry to sixteen bit precision`() {
        val coast=liveCoast(board);val pixels=liveCoastTexture(coast)
        for (y in 0 until LIVE_COAST_TEXTURE_SIZE step 7) for (x in 0 until LIVE_COAST_TEXTURE_SIZE step 7) {
            val i=(y*LIVE_COAST_TEXTURE_SIZE+x)*4
            val packed=((pixels[i].toInt() and 255) shl 8)+(pixels[i+1].toInt() and 255)
            val decoded=packed/65535f*8f-4f
            val p=Offset(((x+.5f)/LIVE_COAST_TEXTURE_SIZE*2-1)*LIVE_COAST_TEXTURE_EXTENT,
                ((y+.5f)/LIVE_COAST_TEXTURE_SIZE*2-1)*LIVE_COAST_TEXTURE_EXTENT)
            assertEquals(liveCoastDistance(coast,p).coerceIn(-4f,4f),decoded,.00013f)
        }
    }

    @Test fun `water phase keys reverse without a seam and leave simulation time alone`() {
        assertEquals(24, LIVE_WATER_PHASE_KEYS)
        for (i in 0..240) {
            val t = i / 20f
            assertEquals(liveWaterPhase(t), liveWaterPhase(24f - t), .00001f)
            assertEquals(liveWaterPhase(t), liveWaterPhase(24f + t), .00002f)
        }
        assertEquals(0f, liveWaterPhase(0f), 0f)
        assertEquals(12f, liveWaterPhase(12f), 0f)
        for (end in listOf(0f, 12f, 24f)) {
            assertTrue(abs(liveWaterPhase(end + .01f) - liveWaterPhase(end)) < .00003f)
            assertTrue(abs(liveWaterPhase(end - .01f) - liveWaterPhase(end)) < .00003f)
        }
        assertThrows(IllegalArgumentException::class.java) { liveWaterPhase(Float.NaN) }
        val sea = Sea(board, 17); sea.update(.05f)
        val time = sea.time
        liveWaterPhase(time)
        assertEquals(time, sea.time, 0f)
        for (t in listOf(0f, .05f, 3f, 7f, 11.95f, 12f)) {
            fun at(seconds: Float) = Sea(board, 17).apply { update(seconds / (.25f + 1.5f * motion)) }
            val forward = liveSeaPlacements(at(t), false).filter { it.model == "sea_wavelet" }
            val reverse = liveSeaPlacements(at(24f - t), false).filter { it.model == "sea_wavelet" }
            assertEquals(forward.size, reverse.size)
            for ((a, b) in forward.zip(reverse)) {
                close(a.position, b.position)
                assertEquals(a.height, b.height, .00001f)
                assertEquals(a.alpha, b.alpha, .00001f)
            }
        }
    }

    @Test fun `waves stay below the tiles and boats ride the same sea surface at every density`() {
        val coast=liveCoast(board)
        for (strength in listOf(0f,.65f,1f,1.35f)) for (t in 0..200) for (edge in coast) {
            val point=edge.center+edge.outward*(LIVE_COAST_FOOT+.7f)
            val height=liveWaterHeight(coast,point,t*.17f,strength)
            assertTrue(abs(height-LIVE_WATER_HEIGHT)<=.06001f*strength)
            assertTrue(height<-.15f)
        }
        val sea=Sea(board,19);sea.waves01=1f;sea.update(.05f)
        for (boat in liveSeaPlacements(sea,false).filter { it.model=="live_sloop" }) {
            assertEquals(liveWaterHeight(coast,boat.position,sea.time,sea.whitecapScale),boat.height,.00001f)
        }
    }
}
