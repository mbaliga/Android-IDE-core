package dev.fonebrew.domain.search.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class VectorMathTest {

    @Test fun `dot, norm and cosine on known vectors`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0f, VectorMath.dot(a, b), 1e-6f)
        assertEquals(5f, VectorMath.norm(floatArrayOf(3f, 4f)), 1e-6f)
        assertEquals(1f, VectorMath.cosine(floatArrayOf(2f, 0f), floatArrayOf(5f, 0f)), 1e-6f)
        assertEquals(-1f, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(-3f, 0f)), 1e-6f)
    }

    @Test fun `zero vector yields zero similarity and zero normalisation, never NaN`() {
        val z = FloatArray(3)
        assertEquals(0f, VectorMath.cosine(z, floatArrayOf(1f, 2f, 3f)), 0f)
        assertTrue(VectorMath.normalized(z).all { it == 0f })
        assertTrue(VectorMath.normalized(floatArrayOf(Float.NaN, 1f)).all { it == 0f })
    }

    @Test fun `normalized has unit length`() {
        assertEquals(1f, VectorMath.norm(VectorMath.normalized(floatArrayOf(3f, 4f, 12f))), 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class) fun `dot rejects mismatched dimensions`() {
        VectorMath.dot(floatArrayOf(1f), floatArrayOf(1f, 2f))
    }

    @Test fun `isFinite catches NaN and infinity`() {
        assertTrue(VectorMath.isFinite(floatArrayOf(1f, 2f)))
        assertFalse(VectorMath.isFinite(floatArrayOf(1f, Float.POSITIVE_INFINITY)))
        assertFalse(VectorMath.isFinite(floatArrayOf(Float.NaN)))
    }
}

class TopKTest {
    private fun e(doc: String, chunk: Int, vararg v: Float) = VectorEntry(doc, chunk, v)

    @Test fun `returns best first`() {
        val r = TopK.chunks(floatArrayOf(1f, 0f), sequenceOf(e("a", 0, 0.2f, 0f), e("b", 0, 0.9f, 0f), e("c", 0, 0.5f, 0f)), 2)
        assertEquals(listOf("b", "c"), r.map { it.docId })
    }

    @Test fun `k larger than corpus returns everything`() {
        val r = TopK.chunks(floatArrayOf(1f), sequenceOf(e("a", 0, 1f), e("b", 0, 2f)), 50)
        assertEquals(2, r.size)
    }

    @Test fun `empty store and non-positive k give empty`() {
        assertTrue(TopK.chunks(floatArrayOf(1f), emptySequence(), 5).isEmpty())
        assertTrue(TopK.chunks(floatArrayOf(1f), sequenceOf(e("a", 0, 1f)), 0).isEmpty())
        assertTrue(TopK.docs(floatArrayOf(1f), sequenceOf(e("a", 0, 1f)), -1).isEmpty())
        assertTrue(TopK.chunks(FloatArray(0), sequenceOf(e("a", 0, 1f)), 3).isEmpty())
    }

    @Test fun `ties break deterministically on doc then chunk`() {
        val entries = sequenceOf(e("z", 0, 1f), e("a", 1, 1f), e("a", 0, 1f), e("m", 0, 1f))
        val r = TopK.chunks(floatArrayOf(1f), entries, 3)
        assertEquals(listOf("a" to 0, "a" to 1, "m" to 0), r.map { it.docId to it.chunk })
        // Same answer whatever the arrival order.
        val rev = TopK.chunks(floatArrayOf(1f), entries.toList().reversed().asSequence(), 3)
        assertEquals(r, rev)
    }

    @Test fun `bounded heap evicts the weakest when full`() {
        val entries = (1..100).asSequence().map { e("d$it", 0, it.toFloat()) }
        val r = TopK.chunks(floatArrayOf(1f), entries, 3)
        assertEquals(listOf("d100", "d99", "d98"), r.map { it.docId })
    }

    @Test fun `docs max-pools chunks`() {
        val r = TopK.docs(floatArrayOf(1f, 0f), sequenceOf(e("a", 0, 0.1f, 0f), e("a", 1, 0.8f, 0f), e("b", 0, 0.5f, 0f)), 5)
        assertEquals(listOf("a", "b"), r.map { it.docId })
        assertEquals(1, r[0].bestChunk)
    }

    @Test fun `entries of a different dimension are skipped, not compared`() {
        val r = TopK.docs(floatArrayOf(1f, 0f), sequenceOf(e("wrong", 0, 1f, 0f, 0f), e("ok", 0, 0.1f, 0f)), 5)
        assertEquals(listOf("ok"), r.map { it.docId })
    }
}
