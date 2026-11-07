const encoder = new TextEncoder();

export function base64ToBytes(value: string): Uint8Array {
  const cleaned = value.trim().replace(/\s+/g, "");
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(cleaned) || cleaned.length % 4 !== 0) {
    throw new Error("Invalid base64 value.");
  }
  const binary = atob(cleaned);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return bytesToHex(new Uint8Array(digest));
}

function readDerLength(bytes: Uint8Array, offset: number): { length: number; next: number } {
  if (offset >= bytes.length) throw new Error("Truncated ECDSA signature.");
  const first = bytes[offset];
  if (first < 0x80) return { length: first, next: offset + 1 };
  const count = first & 0x7f;
  if (count === 0 || count > 2 || offset + 1 + count > bytes.length) {
    throw new Error("Invalid ECDSA signature length.");
  }
  let length = 0;
  for (let i = 0; i < count; i++) length = (length << 8) | bytes[offset + 1 + i];
  if (length < 0x80) throw new Error("Non-canonical ECDSA signature length.");
  return { length, next: offset + 1 + count };
}

function readDerInteger(bytes: Uint8Array, offset: number): { value: Uint8Array; next: number } {
  if (offset >= bytes.length || bytes[offset] !== 0x02) {
    throw new Error("Invalid ECDSA signature integer.");
  }
  const parsedLength = readDerLength(bytes, offset + 1);
  const end = parsedLength.next + parsedLength.length;
  if (parsedLength.length < 1 || end > bytes.length) throw new Error("Truncated ECDSA signature integer.");
  let value = bytes.slice(parsedLength.next, end);
  if ((value[0] & 0x80) !== 0) throw new Error("Negative ECDSA signature integer.");
  while (value.length > 1 && value[0] === 0) value = value.slice(1);
  if (value.length > 32) throw new Error("ECDSA P-256 signature integer is too large.");
  return { value, next: end };
}

/** Convert Android/JCA ASN.1 DER ECDSA signatures to WebCrypto's fixed-width r||s format. */
export function derEcdsaToP1363(der: Uint8Array): Uint8Array {
  if (der.length < 8 || der[0] !== 0x30) throw new Error("Invalid DER ECDSA signature.");
  const sequenceLength = readDerLength(der, 1);
  if (sequenceLength.next + sequenceLength.length !== der.length) {
    throw new Error("Invalid DER ECDSA signature sequence.");
  }
  const r = readDerInteger(der, sequenceLength.next);
  const s = readDerInteger(der, r.next);
  if (s.next !== der.length) throw new Error("Unexpected data after ECDSA signature.");
  const raw = new Uint8Array(64);
  raw.set(r.value, 32 - r.value.length);
  raw.set(s.value, 64 - s.value.length);
  return raw;
}

export async function verifyEcdsaP256Signature(
  publicKeyBase64: string,
  signatureBase64: string,
  message: string,
): Promise<boolean> {
  const spki = base64ToBytes(publicKeyBase64);
  const derSignature = base64ToBytes(signatureBase64);
  const signature = derEcdsaToP1363(derSignature);
  const publicKey = await crypto.subtle.importKey(
    "spki",
    spki,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );
  return await crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    publicKey,
    signature,
    encoder.encode(message),
  );
}
