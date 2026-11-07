import {
  assertEquals,
  assertThrows,
} from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  base64ToBytes,
  bytesToHex,
  derEcdsaToP1363,
  sha256Hex,
  verifyEcdsaP256Signature,
} from "./crypto.ts";

function toBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const value of bytes) binary += String.fromCharCode(value);
  return btoa(binary);
}

function encodeDerLength(length: number): number[] {
  if (length < 0x80) return [length];
  return [0x81, length];
}

function derInteger(value: Uint8Array): number[] {
  let normalized = value;
  while (normalized.length > 1 && normalized[0] === 0) normalized = normalized.slice(1);
  if ((normalized[0] & 0x80) !== 0) normalized = new Uint8Array([0, ...normalized]);
  return [0x02, ...encodeDerLength(normalized.length), ...normalized];
}

function rawToDer(raw: Uint8Array): Uint8Array {
  const r = raw.slice(0, 32);
  const s = raw.slice(32, 64);
  const body = [...derInteger(r), ...derInteger(s)];
  return new Uint8Array([0x30, ...encodeDerLength(body.length), ...body]);
}

Deno.test("converts DER ECDSA signatures into 64-byte P1363", () => {
  const r = new Uint8Array(32);
  const s = new Uint8Array(32);
  r[31] = 1;
  s[31] = 2;
  const der = rawToDer(new Uint8Array([...r, ...s]));
  const converted = derEcdsaToP1363(der);
  assertEquals(converted.length, 64);
  assertEquals(bytesToHex(converted.slice(0, 32)), bytesToHex(r));
  assertEquals(bytesToHex(converted.slice(32)), bytesToHex(s));
});

Deno.test("handles positive integers that have DER sign-padding bytes", () => {
  const r = new Uint8Array(32);
  const s = new Uint8Array(32);
  r[0] = 0x80;
  s[0] = 0xff;
  const converted = derEcdsaToP1363(rawToDer(new Uint8Array([...r, ...s])));
  assertEquals(converted.length, 64);
  assertEquals(converted[0], 0x80);
  assertEquals(converted[32], 0xff);
});

Deno.test("rejects malformed and oversized DER signatures", () => {
  assertThrows(() => derEcdsaToP1363(new Uint8Array([0x30, 0x00])));
  assertThrows(() => derEcdsaToP1363(new Uint8Array([0x31, 0x00])));
  const tooLargeInteger = new Uint8Array([0x30, 0x25, 0x02, 0x21, ...new Uint8Array(33), 0x02, 0x00]);
  assertThrows(() => derEcdsaToP1363(tooLargeInteger));
});

Deno.test("hashes challenges with SHA-256 and validates base64 strictly", async () => {
  assertEquals(
    await sha256Hex("hello"),
    "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
  );
  assertEquals(base64ToBytes("AQID").length, 3);
  assertThrows(() => base64ToBytes("not base64!"));
});

Deno.test("verifies an ECDSA P-256 signature and rejects a modified message", async () => {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const publicKey = new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey));
  const message = "beekeep-device-proof-test";
  const rawSignature = new Uint8Array(await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    pair.privateKey,
    new TextEncoder().encode(message),
  ));
  const derSignature = rawToDer(rawSignature);
  assertEquals(
    await verifyEcdsaP256Signature(toBase64(publicKey), toBase64(derSignature), message),
    true,
  );
  assertEquals(
    await verifyEcdsaP256Signature(toBase64(publicKey), toBase64(derSignature), message + "-modified"),
    false,
  );
});
