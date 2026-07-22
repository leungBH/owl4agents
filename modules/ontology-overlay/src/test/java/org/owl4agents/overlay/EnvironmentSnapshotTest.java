package org.owl4agents.overlay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.8.7 OV-004 unit tests for {@link EnvironmentSnapshot} and
 * {@link StateSnapshotId} records.
 *
 * <p>Verifies the snapshot identifier fields (snapshotId/capturedAt/
 * source/version/checksum), defaults, immutability, and the
 * {@link EnvironmentSnapshot#toSnapshotId()} view.</p>
 */
@DisplayName("EnvironmentSnapshot: snapshot identifier and immutability")
class EnvironmentSnapshotTest {

    @Test
    @DisplayName("OV-004: EnvironmentSnapshot carries all five metadata fields")
    void snapshotCarriesAllMetadataFields() {
        Instant now = Instant.now();
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
            "snap-1", now, "mcp", 5L, "abcdef0123456789",
            List.of(), List.of(), List.of());
        assertEquals("snap-1", snapshot.snapshotId());
        assertEquals(now, snapshot.capturedAt());
        assertEquals("mcp", snapshot.source());
        assertEquals(5L, snapshot.version());
        assertEquals("abcdef0123456789", snapshot.checksum());
    }

    @Test
    @DisplayName("OV-004: blank snapshotId is rejected")
    void blankSnapshotIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentSnapshot(
            "", Instant.now(), "mcp", 0L, "",
            List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentSnapshot(
            null, Instant.now(), "mcp", 0L, "",
            List.of(), List.of(), List.of()));
    }

    @Test
    @DisplayName("OV-004: null capturedAt is rejected")
    void nullCapturedAtRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentSnapshot(
            "snap-2", null, "mcp", 0L, "",
            List.of(), List.of(), List.of()));
    }

    @Test
    @DisplayName("OV-004: blank source defaults to 'api'")
    void blankSourceDefaultsToApi() {
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
            "snap-3", Instant.now(), null, 0L, "",
            List.of(), List.of(), List.of());
        assertEquals("api", snapshot.source());

        EnvironmentSnapshot blankSnapshot = new EnvironmentSnapshot(
            "snap-4", Instant.now(), "  ", 0L, "",
            List.of(), List.of(), List.of());
        assertEquals("api", blankSnapshot.source());
    }

    @Test
    @DisplayName("OV-004: negative version clamped to 0")
    void negativeVersionClampedToZero() {
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
            "snap-5", Instant.now(), "api", -1L, "",
            List.of(), List.of(), List.of());
        assertEquals(0L, snapshot.version());
    }

    @Test
    @DisplayName("OV-004: null checksum coerced to empty string")
    void nullChecksumCoercedToEmpty() {
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
            "snap-6", Instant.now(), "api", 0L, null,
            List.of(), List.of(), List.of());
        assertEquals("", snapshot.checksum());
    }

    @Test
    @DisplayName("OV-004: null device/user/toolcall lists default to empty")
    void nullListsDefaultToEmpty() {
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
            "snap-7", Instant.now(), "api", 0L, "",
            null, null, null);
        assertTrue(snapshot.devices().isEmpty());
        assertTrue(snapshot.userContexts().isEmpty());
        assertTrue(snapshot.pendingToolCalls().isEmpty());
    }

    @Test
    @DisplayName("OV-004: device/user/toolcall lists are defensively copied (immutable)")
    void listsAreImmutable() {
        DeviceSnapshot device = new DeviceSnapshot(
            "https://example.org/d1", "https://example.org/Device",
            null, "on");
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
            "snap-8", Instant.now(), "api", 0L, "",
            List.of(device), List.of(), List.of());
        // The returned list should be immutable.
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.devices().add(device));
    }

    @Test
    @DisplayName("OV-004: toSnapshotId() returns the metadata subset")
    void toSnapshotIdReturnsMetadataSubset() {
        Instant now = Instant.now();
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
            "snap-9", now, "cli", 7L, "checksum-123",
            List.of(), List.of(), List.of());
        StateSnapshotId snapshotId = snapshot.toSnapshotId();
        assertEquals("snap-9", snapshotId.snapshotId());
        assertEquals(now, snapshotId.capturedAt());
        assertEquals("cli", snapshotId.source());
        assertEquals(7L, snapshotId.version());
        assertEquals("checksum-123", snapshotId.checksum());
    }

    @Test
    @DisplayName("OV-004: StateSnapshotId validates the same fields")
    void stateSnapshotIdValidation() {
        Instant now = Instant.now();
        StateSnapshotId id = new StateSnapshotId("id-1", now, "mcp", 3L, "abc");
        assertEquals("id-1", id.snapshotId());
        assertEquals(now, id.capturedAt());
        assertEquals("mcp", id.source());
        assertEquals(3L, id.version());
        assertEquals("abc", id.checksum());

        // Blank snapshotId rejected.
        assertThrows(IllegalArgumentException.class,
            () -> new StateSnapshotId("", now, "mcp", 0L, ""));
        // Null capturedAt rejected.
        assertThrows(IllegalArgumentException.class,
            () -> new StateSnapshotId("id-2", null, "mcp", 0L, ""));
        // Blank source defaults to "api".
        StateSnapshotId blankSource = new StateSnapshotId("id-3", now, null, 0L, "");
        assertEquals("api", blankSource.source());
        // Negative version clamped.
        StateSnapshotId negVersion = new StateSnapshotId("id-4", now, "api", -1L, "");
        assertEquals(0L, negVersion.version());
        // Null checksum coerced.
        StateSnapshotId nullChecksum = new StateSnapshotId("id-5", now, "api", 0L, null);
        assertEquals("", nullChecksum.checksum());
    }

    @Test
    @DisplayName("OV-004: EnvironmentSnapshot with structured devices carries them through")
    void snapshotCarriesStructuredDevices() {
        DeviceSnapshot device1 = new DeviceSnapshot(
            "https://example.org/d1", "https://example.org/SmartPlug",
            "https://example.org/LivingRoom", "on");
        DeviceSnapshot device2 = new DeviceSnapshot(
            "https://example.org/d2", "https://example.org/HVAC",
            null, "off");
        UserContext user = new UserContext(
            "https://example.org/u1", Optional.of("https://example.org/LivingRoom"),
            List.of("prefers-cool"), List.of("https://example.org/Admin"));
        ToolCallCandidate call = new ToolCallCandidate(
            "call-1", "set_temperature", "https://example.org/d2",
            Map.of("target", "22"), "https://example.org/u1");

        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
            "snap-10", Instant.now(), "api", 0L, "",
            List.of(device1, device2), List.of(user), List.of(call));

        assertEquals(2, snapshot.devices().size());
        assertEquals(1, snapshot.userContexts().size());
        assertEquals(1, snapshot.pendingToolCalls().size());
        assertEquals("set_temperature", snapshot.pendingToolCalls().get(0).toolName());
    }
}
