package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.ContractHello
import dev.fonebrew.watchbridge.VersionMismatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTest {
    private fun reduce(c: Connection, e: LinkEvent) = ConnectionReducer.reduce(c, e)

    // ---- picker ----

    @Test
    fun picker_prefersNearby_thenStableById() {
        val far = ObservedNode("a", "Far", nearby = false)
        val near2 = ObservedNode("z", "Near Z", nearby = true)
        val near1 = ObservedNode("m", "Near M", nearby = true)
        assertEquals(PhoneNode("m", "Near M"), PhoneNodePicker.pick(listOf(far, near2, near1)))
        assertEquals(PhoneNode("a", "Far"), PhoneNodePicker.pick(listOf(far)))
        assertNull(PhoneNodePicker.pick(emptyList()))
    }

    @Test
    fun picker_blankName_getsALegibleDefault() {
        assertEquals("Phone", PhoneNodePicker.pick(listOf(ObservedNode("n", "  ", true)))!!.displayName)
    }

    // ---- node observations ----

    @Test
    fun checking_thenANode_isReachableWithUnknownVersion() {
        val c = reduce(Connection.Checking, LinkEvent.NodesObserved(listOf(Fx.observed)))
        assertEquals(Connection.Reachable(Fx.phone, null), c)
    }

    @Test
    fun noNodes_isUnreachable_fromAnyState() {
        for (start in listOf(Connection.Checking, Connection.Reachable(Fx.phone, "0.3.1"), Connection.Incompatible(VersionMismatch(2, 1), Fx.phone))) {
            assertEquals(Connection.Unreachable(UnreachableCause.NO_PHONE_CONNECTED), reduce(start, LinkEvent.NodesObserved(emptyList())))
        }
    }

    @Test
    fun nodeReappearing_clearsNoPhone_andLinkUnavailable() {
        for (cause in listOf(UnreachableCause.NO_PHONE_CONNECTED, UnreachableCause.LINK_UNAVAILABLE)) {
            assertEquals(Connection.Reachable(Fx.phone, null), reduce(Connection.Unreachable(cause), LinkEvent.NodesObserved(listOf(Fx.observed))))
        }
    }

    @Test
    fun aListedNode_doesNotProveAFailedLinkCallWouldNowWork() {
        val failed = Connection.Unreachable(UnreachableCause.LINK_ERROR)
        assertEquals(failed, reduce(failed, LinkEvent.NodesObserved(listOf(Fx.observed))))
    }

    @Test
    fun sameNode_keepsTheKnownVersion_aDifferentNodeForgetsIt() {
        val known = Connection.Reachable(Fx.phone, "0.3.1")
        assertEquals(known, reduce(known, LinkEvent.NodesObserved(listOf(Fx.observed))))
        val other = ObservedNode("node-2", "Other", true)
        assertEquals(Connection.Reachable(PhoneNode("node-2", "Other"), null), reduce(known, LinkEvent.NodesObserved(listOf(other))))
    }

    // ---- hello / incompatibility ----

    @Test
    fun compatibleHello_confirmsTheFonebrewVersion() {
        val c = reduce(Connection.Checking, LinkEvent.HelloReceived("node-1", ContractHello.forThisBuild("0.3.1")))
        assertEquals(Connection.Reachable(PhoneNode("node-1", "Phone"), "0.3.1"), c)
    }

    @Test
    fun helloFromAnotherContractVersion_isIncompatible_andNamesWhoIsNewer() {
        val c = reduce(Connection.Checking, LinkEvent.HelloReceived("node-1", ContractHello(contractVersion = 2, phoneAppVersion = "9.0")))
        assertTrue(c is Connection.Incompatible)
        val mismatch = (c as Connection.Incompatible).mismatch
        assertTrue(mismatch.peerIsNewer)
        assertTrue(mismatch.reason.contains("Update this app"))
    }

    @Test
    fun incompatibility_isLiftedOnlyByACompatibleHello_notByTraffic_orNodeLists() {
        val inc = Connection.Incompatible(VersionMismatch(2, 1), Fx.phone)
        assertEquals(inc, reduce(inc, LinkEvent.PhoneHeard("node-1")))
        assertEquals(inc, reduce(inc, LinkEvent.NodesObserved(listOf(Fx.observed))))
        assertEquals(inc, reduce(inc, LinkEvent.CallFailed(LinkFailure.OTHER)))
        val healed = reduce(inc, LinkEvent.HelloReceived("node-1", ContractHello.forThisBuild("0.4.0")))
        assertEquals(Connection.Reachable(PhoneNode("node-1", "Pixel"), "0.4.0"), healed)
    }

    @Test
    fun incompatibleEnvelope_setsIncompatible_evenWithoutKnowingTheNode() {
        val c = reduce(Connection.Checking, LinkEvent.EnvelopeIncompatible(null, VersionMismatch(1, 2)))
        assertEquals(Connection.Incompatible(VersionMismatch(1, 2), null), c)
        assertFalse((c as Connection.Incompatible).mismatch.peerIsNewer)
    }

    // ---- proof of life / failures ----

    @Test
    fun phoneHeard_makesUnreachableReachable_withoutInventingAVersion() {
        val c = reduce(Connection.Unreachable(UnreachableCause.LINK_ERROR), LinkEvent.PhoneHeard("node-1"))
        assertEquals(Connection.Reachable(PhoneNode("node-1", "Phone"), null), c)
    }

    @Test
    fun sendSucceeded_clearsUnreachable_butDoesNotTouchAKnownGoodState() {
        assertEquals(Connection.Reachable(PhoneNode("node-1", "Phone"), null), reduce(Connection.Unreachable(UnreachableCause.NO_PHONE_CONNECTED), LinkEvent.SendSucceeded("node-1")))
        val known = Connection.Reachable(Fx.phone, "0.3.1")
        assertEquals(known, reduce(known, LinkEvent.SendSucceeded("node-1")))
    }

    @Test
    fun callFailures_mapToTheirOwnUnreachableCauses() {
        assertEquals(Connection.Unreachable(UnreachableCause.NO_PHONE_CONNECTED), reduce(Connection.Checking, LinkEvent.CallFailed(LinkFailure.NO_NODE)))
        assertEquals(Connection.Unreachable(UnreachableCause.LINK_UNAVAILABLE), reduce(Connection.Checking, LinkEvent.CallFailed(LinkFailure.API_UNAVAILABLE)))
        assertEquals(Connection.Unreachable(UnreachableCause.LINK_ERROR), reduce(Connection.Reachable(Fx.phone, null), LinkEvent.CallFailed(LinkFailure.OTHER)))
    }

    // ---- presenter: words + shape, never a bare spinner ----

    @Test
    fun everyConnectionState_hasAShape_aHeadline_andADetail() {
        val all = listOf(
            Connection.Checking,
            Connection.Reachable(Fx.phone, null),
            Connection.Reachable(Fx.phone, "0.3.1"),
            Connection.Unreachable(UnreachableCause.NO_PHONE_CONNECTED),
            Connection.Unreachable(UnreachableCause.LINK_ERROR),
            Connection.Unreachable(UnreachableCause.LINK_UNAVAILABLE),
            Connection.Incompatible(VersionMismatch(2, 1), Fx.phone),
        )
        for (c in all) {
            val v = ConnectionPresenter.present(c)
            assertTrue("headline for $c", v.headline.isNotBlank())
            assertTrue("detail for $c", v.detail.isNotBlank())
        }
    }

    @Test
    fun blockingStates_areExactlyUnreachableAndIncompatible() {
        assertFalse(ConnectionPresenter.present(Connection.Checking).blocking)
        assertFalse(ConnectionPresenter.present(Connection.Reachable(Fx.phone, null)).blocking)
        assertTrue(ConnectionPresenter.present(Connection.Unreachable(UnreachableCause.NO_PHONE_CONNECTED)).blocking)
        assertTrue(ConnectionPresenter.present(Connection.Incompatible(VersionMismatch(2, 1), null)).blocking)
    }

    @Test
    fun unreachableAndCheckingAndLinked_useDifferentShapes() {
        val shapes = listOf(
            ConnectionPresenter.present(Connection.Checking).glyph,
            ConnectionPresenter.present(Connection.Reachable(Fx.phone, null)).glyph,
            ConnectionPresenter.present(Connection.Unreachable(UnreachableCause.NO_PHONE_CONNECTED)).glyph,
            ConnectionPresenter.present(Connection.Incompatible(VersionMismatch(2, 1), null)).glyph,
        )
        assertEquals(shapes.size, shapes.toSet().size)
    }

    @Test
    fun reachableWithoutAHello_saysFonebrewHasNotSpokenYet() {
        assertEquals("Fonebrew hasn't said hello yet.", ConnectionPresenter.present(Connection.Reachable(Fx.phone, null)).detail)
        assertEquals("Fonebrew 0.3.1", ConnectionPresenter.present(Connection.Reachable(Fx.phone, "0.3.1")).detail)
    }
}
