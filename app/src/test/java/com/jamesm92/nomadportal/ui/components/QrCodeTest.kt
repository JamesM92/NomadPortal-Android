package com.jamesm92.nomadportal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Real unit-test coverage for the identity-QR build/parse/normalize
 * logic (`buildIdentityQrPayload`/`parseIdentityQrPayload`/
 * `normalizeScannedText`) — the one part of [QrScannerOverlay]'s scan
 * pipeline that's plain string logic, not CameraX/ZXing image
 * processing. That other half (`decodeQrFromImageProxy`) needs a real
 * camera frame and can't run in a plain JVM test.
 *
 * Written per explicit direction ("proceed with what you can") after a
 * real, honestly-documented gap: a live device-to-device camera scan
 * was never actually achievable in this project's dev environment —
 * one emulator, no second device/camera to scan a code with (see the
 * columba-fresh-audit-round2 memory's own note on this). This doesn't
 * replace that missing real-camera round trip, but it's real,
 * deterministic, automated coverage for the logic that would actually
 * contain a bug if one existed — malformed scans, case handling, the
 * fallback-to-bare-hex path — none of which this project had any
 * automated Kotlin-side test for before now (app/src/test only had the
 * default template ExampleUnitTest.kt).
 *
 * Real hash/key lengths matter here, not just "some hex string": a
 * genuine RNS destination hash is 16 bytes (32 hex chars) and this
 * app's own `lxma://` public key field is 64 bytes (128 hex chars) —
 * X25519 (32) + Ed25519 (32), the same real `RNS.Identity` public-key
 * layout the identity-import work earlier this session confirmed
 * directly against RNS's own source. Columba's own
 * `IdentityQrCodeUtils` (fetched and checked directly, not recalled)
 * enforces those exact lengths on decode; this app's own
 * `parseIdentityQrPayload` deliberately doesn't (see that function's
 * neighbor, `AddByAddressDialog`'s own doc comment: "this only checks
 * [hex], not length ... an invalid or unreachable one still opens ...
 * the normal way" — a considered app-wide choice, not an oversight),
 * so the malformed-length cases below assert this app's own real,
 * looser contract, not Columba's stricter one.
 */
class QrCodeTest {

    private val realHash = "3ec66c05c8ee96f7b6cb9a0d5f3e2c14"
    private val realPubKey =
        "5ba3c9d2e1f4a6b8c0d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a3b5c7d9e1f3a5b7" +
            "9b6bc8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c6d8e0f2a4b6"

    // ---------------------------------------------------------------
    // buildIdentityQrPayload / parseIdentityQrPayload round trip
    // ---------------------------------------------------------------

    @Test
    fun `build then parse round-trips exactly`() {
        val payload = buildIdentityQrPayload(realHash, realPubKey)
        assertEquals("lxma://$realHash:$realPubKey", payload)

        val parsed = parseIdentityQrPayload(payload)
        assertEquals(ScannedIdentity(realHash, realPubKey), parsed)
    }

    @Test
    fun `parse is case-insensitive and normalizes to lowercase`() {
        val upper = "lxma://${realHash.uppercase()}:${realPubKey.uppercase()}"
        val parsed = parseIdentityQrPayload(upper)
        assertEquals(ScannedIdentity(realHash, realPubKey), parsed)
    }

    @Test
    fun `parse matches Columba's own real lxma format exactly`() {
        // Same literal shape as network.columba.app.util.IdentityQrCodeUtils
        // .encodeToQrString's own real output (fetched from source):
        // "lxma://<32 hex>:<128 hex>", nothing else — confirming a code
        // that app generates parses cleanly here.
        val columbaShaped = "lxma://$realHash:$realPubKey"
        val parsed = parseIdentityQrPayload(columbaShaped)
        assertEquals(realHash, parsed?.destinationHash)
        assertEquals(realPubKey, parsed?.publicKeyHex)
    }

    // ---------------------------------------------------------------
    // parseIdentityQrPayload — real failure modes a camera could hand it
    // ---------------------------------------------------------------

    @Test
    fun `parse rejects text with no lxma prefix`() {
        assertNull(parseIdentityQrPayload(realHash))
        assertNull(parseIdentityQrPayload("https://example.com"))
    }

    @Test
    fun `parse rejects a payload with no colon separator`() {
        assertNull(parseIdentityQrPayload("lxma://$realHash$realPubKey"))
    }

    @Test
    fun `parse rejects a payload with too many colons`() {
        assertNull(parseIdentityQrPayload("lxma://$realHash:$realPubKey:extra"))
    }

    @Test
    fun `parse rejects odd-length hex on either side`() {
        assertNull(parseIdentityQrPayload("lxma://${realHash}f:$realPubKey"))
        assertNull(parseIdentityQrPayload("lxma://$realHash:${realPubKey}f"))
    }

    @Test
    fun `parse rejects non-hex characters`() {
        val badHash = "zz" + realHash.drop(2)
        assertNull(parseIdentityQrPayload("lxma://$badHash:$realPubKey"))
    }

    @Test
    fun `parse rejects empty hash or key`() {
        assertNull(parseIdentityQrPayload("lxma://:$realPubKey"))
        assertNull(parseIdentityQrPayload("lxma://$realHash:"))
    }

    @Test
    fun `parse tolerates surrounding whitespace from a real scan`() {
        // A real ZXing decode result can carry trailing whitespace/newlines
        // depending on the code's own error-correction padding — trim()
        // happens before the prefix check, not after.
        val parsed = parseIdentityQrPayload("  lxma://$realHash:$realPubKey\n")
        assertEquals(ScannedIdentity(realHash, realPubKey), parsed)
    }

    // ---------------------------------------------------------------
    // normalizeScannedText — the real dispatch QrScannerOverlay uses
    // ---------------------------------------------------------------

    @Test
    fun `normalize prefers the lxma identity shape when present`() {
        val normalized = normalizeScannedText("lxma://$realHash:$realPubKey")
        assertEquals(ScannedIdentity(realHash, realPubKey), normalized)
    }

    @Test
    fun `normalize falls back to a bare hex address`() {
        // No lxma prefix at all -- this app's own pre-lxma QR codes, or a
        // code from a client that only shares a bare address.
        val normalized = normalizeScannedText(realHash)
        assertEquals(ScannedIdentity(realHash, null), normalized)
    }

    @Test
    fun `normalize extracts a bare address from a URL-shaped scan`() {
        // substringAfterLast('/') / substringAfterLast(':') -- covers a
        // scan like "hash://<hex>" or a path-shaped scheme, matching
        // NodeListScreen's own real "hash://" convention elsewhere in
        // this app.
        val normalized = normalizeScannedText("hash://$realHash")
        assertEquals(ScannedIdentity(realHash, null), normalized)
    }

    @Test
    fun `normalize ignores a code that decodes to unrelated text`() {
        // The overwhelmingly common real case: a camera pointed at the
        // world sees plenty of QR codes (posters, product packaging) that
        // aren't an address at all -- QrScannerOverlay keeps scanning
        // rather than treating this as an error.
        assertNull(normalizeScannedText("https://example.com/not-an-address"))
        assertNull(normalizeScannedText("just some plain text"))
    }
}
