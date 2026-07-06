package dev.aarso.domain.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure spatial-linearization model. Covers the announce
 * strings (per room + per Z-depth), the compass adjacency, Back targets, the
 * deterministic TalkBack reading order, RTL mirroring, and determinism.
 */
class SpatialLinearizationTest {

    // --- announce, per room -------------------------------------------------

    @Test fun announceChatIsHome() {
        assertEquals("Chat, home", SpatialLinearization.announce(Room.CHAT))
    }

    @Test fun announceConversationsRoom() {
        assertEquals("Conversations room", SpatialLinearization.announce(Room.CONVERSATIONS))
    }

    @Test fun announceSettingsRoom() {
        assertEquals("Settings room", SpatialLinearization.announce(Room.SETTINGS))
    }

    @Test fun announceProjectRoom() {
        assertEquals("Project room", SpatialLinearization.announce(Room.PROJECT))
    }

    @Test fun announceDevelopRoom() {
        assertEquals("Develop room", SpatialLinearization.announce(Room.DEVELOP))
    }

    // --- announce, per Z-depth ----------------------------------------------

    @Test fun announceTreeView() {
        val pos = SpatialPosition(Room.CHAT, Depth.TREE)
        assertEquals("Tree view of current conversation", SpatialLinearization.announce(pos))
    }

    @Test fun announceLoopGraph() {
        val pos = SpatialPosition(Room.CHAT, Depth.LOOPS)
        assertEquals("Loop graph", SpatialLinearization.announce(pos))
    }

    @Test fun announceOriginPositionUsesRoomAnnounce() {
        val pos = SpatialPosition(Room.CONVERSATIONS, Depth.ORIGIN)
        assertEquals("Conversations room", SpatialLinearization.announce(pos))
    }

    @Test fun announceZViewIgnoresRoomIdentity() {
        // A Z-view names the layer, not the room it was opened from.
        assertEquals(
            SpatialLinearization.announce(SpatialPosition(Room.DEVELOP, Depth.TREE)),
            SpatialLinearization.announce(SpatialPosition(Room.PROJECT, Depth.TREE)),
        )
    }

    // --- neighbors / compass adjacency --------------------------------------

    @Test fun chatNeighborsLeftIsConversations() {
        assertEquals(Room.CONVERSATIONS, SpatialLinearization.neighbors(Room.CHAT)["left"])
    }

    @Test fun chatNeighborsRightIsSettings() {
        assertEquals(Room.SETTINGS, SpatialLinearization.neighbors(Room.CHAT)["right"])
    }

    @Test fun chatNeighborsUpIsProject() {
        assertEquals(Room.PROJECT, SpatialLinearization.neighbors(Room.CHAT)["up"])
    }

    @Test fun chatNeighborsDownIsDevelop() {
        assertEquals(Room.DEVELOP, SpatialLinearization.neighbors(Room.CHAT)["down"])
    }

    @Test fun chatHasFourNeighbors() {
        assertEquals(4, SpatialLinearization.neighbors(Room.CHAT).size)
    }

    @Test fun cardinalRoomsHaveNoNeighbors() {
        for (room in listOf(Room.CONVERSATIONS, Room.SETTINGS, Room.PROJECT, Room.DEVELOP)) {
            assertTrue(room.name, SpatialLinearization.neighbors(room).isEmpty())
        }
    }

    // --- backTarget ---------------------------------------------------------

    @Test fun backFromEachCardinalReturnsToChatOrigin() {
        val origin = SpatialPosition(Room.CHAT, Depth.ORIGIN)
        for (room in listOf(Room.CONVERSATIONS, Room.SETTINGS, Room.PROJECT, Room.DEVELOP)) {
            assertEquals(room.name, origin, SpatialLinearization.backTarget(SpatialPosition(room)))
        }
    }

    @Test fun backFromTreeViewReturnsToRoomOrigin() {
        assertEquals(
            SpatialPosition(Room.CHAT, Depth.ORIGIN),
            SpatialLinearization.backTarget(SpatialPosition(Room.CHAT, Depth.TREE)),
        )
    }

    @Test fun backFromLoopViewReturnsToRoomOrigin() {
        assertEquals(
            SpatialPosition(Room.CHAT, Depth.ORIGIN),
            SpatialLinearization.backTarget(SpatialPosition(Room.CHAT, Depth.LOOPS)),
        )
    }

    @Test fun backFromChatOriginStaysAtChatOrigin() {
        val origin = SpatialPosition(Room.CHAT, Depth.ORIGIN)
        assertEquals(origin, SpatialLinearization.backTarget(origin))
    }

    // --- linearize ----------------------------------------------------------

    @Test fun linearizeFirstStopIsRoomAnnounce() {
        val stops = SpatialLinearization.linearize(
            SpatialPosition(Room.CONVERSATIONS), "Conversations", listOf("All", "Starred"),
        )
        assertEquals(ReadingStop.Kind.ROOM_ANNOUNCE, stops.first().kind)
        assertEquals("Conversations room", stops.first().label)
    }

    @Test fun linearizeSecondStopIsHeader() {
        val stops = SpatialLinearization.linearize(
            SpatialPosition(Room.SETTINGS), "Settings header", emptyList(),
        )
        assertEquals(ReadingStop.Kind.HEADER, stops[1].kind)
        assertEquals("Settings header", stops[1].label)
    }

    @Test fun linearizeThirdStopIsNavHint() {
        val stops = SpatialLinearization.linearize(
            SpatialPosition(Room.CHAT), "Chat", emptyList(),
        )
        assertEquals(ReadingStop.Kind.NAV_HINT, stops[2].kind)
    }

    @Test fun linearizeChatNavHintNamesAllFourDirections() {
        val hint = SpatialLinearization.linearize(
            SpatialPosition(Room.CHAT), "Chat", emptyList(),
        )[2].label
        assertTrue(hint, hint.contains("left to Conversations"))
        assertTrue(hint, hint.contains("right to Settings"))
        assertTrue(hint, hint.contains("up to Project"))
        assertTrue(hint, hint.contains("down to Develop"))
    }

    @Test fun linearizeCardinalNavHintIsBackToChat() {
        val hint = SpatialLinearization.linearize(
            SpatialPosition(Room.PROJECT), "Project", emptyList(),
        )[2].label
        assertEquals("Back to Chat", hint)
    }

    @Test fun linearizeContentFollowsInInputOrder() {
        val content = listOf("first", "second", "third")
        val stops = SpatialLinearization.linearize(SpatialPosition(Room.CHAT), "Chat", content)
        val contentStops = stops.filter { it.kind == ReadingStop.Kind.CONTENT }
        assertEquals(content, contentStops.map { it.label })
    }

    @Test fun linearizeStopCountIsThreePlusContent() {
        val content = listOf("a", "b", "c", "d")
        val stops = SpatialLinearization.linearize(SpatialPosition(Room.DEVELOP), "Develop", content)
        assertEquals(3 + content.size, stops.size)
    }

    @Test fun linearizeWithNoContentHasExactlyThreeStops() {
        val stops = SpatialLinearization.linearize(SpatialPosition(Room.CHAT), "Chat", emptyList())
        assertEquals(3, stops.size)
        assertEquals(
            listOf(ReadingStop.Kind.ROOM_ANNOUNCE, ReadingStop.Kind.HEADER, ReadingStop.Kind.NAV_HINT),
            stops.map { it.kind },
        )
    }

    @Test fun linearizeZViewAnnouncesLayerNotRoom() {
        val stops = SpatialLinearization.linearize(
            SpatialPosition(Room.CHAT, Depth.TREE), "Tree", listOf("node-1"),
        )
        assertEquals("Tree view of current conversation", stops.first().label)
        assertEquals("Back to Chat", stops[2].label)
    }

    // --- rtlMirror ----------------------------------------------------------

    @Test fun rtlMirrorSwapsLeftAndRight() {
        val mirrored = SpatialLinearization.rtlMirror(SpatialLinearization.neighbors(Room.CHAT))
        assertEquals(Room.CONVERSATIONS, mirrored["right"])
        assertEquals(Room.SETTINGS, mirrored["left"])
    }

    @Test fun rtlMirrorLeavesUpAndDownUnchanged() {
        val mirrored = SpatialLinearization.rtlMirror(SpatialLinearization.neighbors(Room.CHAT))
        assertEquals(Room.PROJECT, mirrored["up"])
        assertEquals(Room.DEVELOP, mirrored["down"])
    }

    @Test fun rtlMirrorPreservesKeyCount() {
        val n = SpatialLinearization.neighbors(Room.CHAT)
        assertEquals(n.size, SpatialLinearization.rtlMirror(n).size)
    }

    @Test fun rtlMirrorTwiceIsIdentity() {
        val n = SpatialLinearization.neighbors(Room.CHAT)
        assertEquals(n, SpatialLinearization.rtlMirror(SpatialLinearization.rtlMirror(n)))
    }

    @Test fun rtlMirrorOfEmptyIsEmpty() {
        assertTrue(SpatialLinearization.rtlMirror(emptyMap()).isEmpty())
    }

    @Test fun rtlActuallyChangesHorizontal() {
        val n = SpatialLinearization.neighbors(Room.CHAT)
        assertNotEquals(n["left"], SpatialLinearization.rtlMirror(n)["left"])
    }

    // --- determinism --------------------------------------------------------

    @Test fun announceIsDeterministic() {
        for (room in Room.entries) {
            assertEquals(SpatialLinearization.announce(room), SpatialLinearization.announce(room))
        }
    }

    @Test fun linearizeIsDeterministic() {
        val pos = SpatialPosition(Room.CHAT)
        val content = listOf("x", "y")
        assertEquals(
            SpatialLinearization.linearize(pos, "Chat", content),
            SpatialLinearization.linearize(pos, "Chat", content),
        )
    }

    @Test fun neighborsAreDeterministicAndOrdered() {
        val a = SpatialLinearization.neighbors(Room.CHAT)
        val b = SpatialLinearization.neighbors(Room.CHAT)
        assertEquals(a.keys.toList(), b.keys.toList())
        assertEquals(listOf("left", "right", "up", "down"), a.keys.toList())
    }
}
