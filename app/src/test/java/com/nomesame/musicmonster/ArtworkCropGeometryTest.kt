package com.nomesame.musicmonster

import com.nomesame.musicmonster.model.ArtworkCrop
import com.nomesame.musicmonster.model.ArtworkCropRect
import org.junit.Assert.*
import org.junit.Test

class ArtworkCropGeometryTest {
    @Test fun portraitTravelsFromTopThroughCenterToBottom() {
        assertEquals(ArtworkCropRect(0, 0, 1024), ArtworkCropGeometry.rectangle(1024, 1536, ArtworkCrop(y = 0f)))
        assertEquals(ArtworkCropRect(0, 256, 1024), ArtworkCropGeometry.rectangle(1024, 1536, ArtworkCrop()))
        assertEquals(ArtworkCropRect(0, 512, 1024), ArtworkCropGeometry.rectangle(1024, 1536, ArtworkCrop(y = 1f)))
    }
    @Test fun landscapeTravelsHorizontallyAndSquareHasNoTravel() {
        assertEquals(ArtworkCropRect(840, 0, 1080), ArtworkCropGeometry.rectangle(1920, 1080, ArtworkCrop(x = 1f)))
        assertEquals(ArtworkCropRect(0, 0, 100), ArtworkCropGeometry.rectangle(100, 100, ArtworkCrop(1f, 0f)))
    }
    @Test fun invalidPositionsAreClampedOrCentered() {
        assertEquals(ArtworkCrop(0f, 1f), ArtworkCropGeometry.normalized(ArtworkCrop(-4f, 5f)))
        assertEquals(ArtworkCrop(), ArtworkCropGeometry.normalized(ArtworkCrop(Float.NaN, Float.POSITIVE_INFINITY)))
    }
    @Test fun dimensionsAndExtremeAspectRatiosStayBounded() {
        assertThrows(IllegalArgumentException::class.java) { ArtworkCropGeometry.rectangle(0, 12, ArtworkCrop()) }
        assertThrows(IllegalArgumentException::class.java) { ArtworkCropGeometry.rectangle(12, -1, ArtworkCrop()) }
        assertEquals(ArtworkCropRect(Int.MAX_VALUE - 1, 0, 1),
            ArtworkCropGeometry.rectangle(Int.MAX_VALUE, 1, ArtworkCrop(1f, 1f)))
    }
    @Test fun imageDraggingMovesOppositeTheCropWindowAndRespectsBounds() {
        assertEquals(ArtworkCrop(0.5f, 0f), ArtworkCropGeometry.drag(ArtworkCrop(), 100f, 64f, 256f, 1024, 1536))
        assertEquals(ArtworkCrop(0.5f, 1f), ArtworkCropGeometry.drag(ArtworkCrop(), 0f, -1000f, 256f, 1024, 1536))
        assertEquals(ArtworkCrop(0f, 0.5f), ArtworkCropGeometry.drag(ArtworkCrop(), 128f, 0f, 256f, 200, 100))
    }
    @Test fun invalidDragGeometryAndSquareNeverDivideByZero() {
        assertEquals(ArtworkCrop(), ArtworkCropGeometry.drag(ArtworkCrop(), 50f, 50f, 256f, 100, 100))
        assertEquals(ArtworkCrop(), ArtworkCropGeometry.drag(ArtworkCrop(), Float.NaN, Float.NaN, 256f, 100, 200))
        assertEquals(ArtworkCrop(), ArtworkCropGeometry.drag(ArtworkCrop(), 50f, 50f, 0f, 100, 200))
    }
    @Test fun zoomProducesSmallerCenteredOrPositionedWindows() {
        assertEquals(ArtworkCropRect(256, 0, 512), ArtworkCropGeometry.rectangle(1024, 1536, ArtworkCrop(0.5f, 0f, 2f)))
        assertEquals(ArtworkCropRect(384, 640, 256), ArtworkCropGeometry.rectangle(1024, 1536, ArtworkCrop(0.5f, 0.5f, 4f)))
    }
    @Test fun zoomClampsInvalidValuesAndOnePixelImagesRemainValid() {
        assertEquals(1f, ArtworkCropGeometry.normalized(ArtworkCrop(zoom = Float.NaN)).zoom, 0f)
        assertEquals(1f, ArtworkCropGeometry.normalized(ArtworkCrop(zoom = 0f)).zoom, 0f)
        assertEquals(4f, ArtworkCropGeometry.normalized(ArtworkCrop(zoom = 9f)).zoom, 0f)
        assertEquals(ArtworkCropRect(0, 0, 1), ArtworkCropGeometry.rectangle(1, 1, ArtworkCrop(zoom = 4f)))
    }
    @Test fun zoomEnablesBothDragAxesAndKeepsTheZoomValue() {
        val result = ArtworkCropGeometry.drag(ArtworkCrop(zoom = 2f), 64f, 64f, 256f, 100, 100)
        assertEquals(ArtworkCrop(0.25f, 0.25f, 2f), result)
    }

    @Test fun bundledDefaultUsesTopHalfWidthWindowAndCustomDefaultKeepsFullCenteredWindow() {
        val bundled = com.nomesame.musicmonster.model.MediaAppearance(true, null, 0f, 0)
        val custom = com.nomesame.musicmonster.model.MediaAppearance(true, "content://fixture/image", 0f, 0)
        assertEquals(ArtworkCropRect(256, 0, 512),
            ArtworkCropGeometry.rectangle(1024, 1536, bundled.crop))
        assertEquals(ArtworkCropRect(0, 256, 1024),
            ArtworkCropGeometry.rectangle(1024, 1536, custom.crop))
    }
}
