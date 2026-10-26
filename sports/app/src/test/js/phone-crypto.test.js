// Checks the phone page's SHA-256 / HMAC / request signing (assets/phone.html) with Node:
//   node app/src/test/js/phone-crypto.test.js        (run by test.sh)
// The signing vector is shared with PhoneControllerTest and was computed with Python's hmac module.
"use strict";
const fs = require("fs");
const path = require("path");
const assert = require("assert");

const html = fs.readFileSync(path.join(__dirname, "../../main/assets/phone.html"), "utf8");
const src = html.slice(html.indexOf("/*crypto-begin*/"), html.indexOf("/*crypto-end*/"));
const module_ = { exports: {} };
new Function("module", src)(module_);
const C = module_.exports;
const hex = (b) => C.hex(b);
const ascii = (s) => C.utf8(s);
const bytes = (h) => Uint8Array.from(h.match(/../g).map((x) => parseInt(x, 16)));

let n = 0;
function check(name, got, want) { assert.strictEqual(got, want, name); n++; }

// FIPS 180-4 examples, plus lengths around the 55/56/64-byte padding boundaries.
check("sha256('')", hex(C.sha256(ascii(""))), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
check("sha256('abc')", hex(C.sha256(ascii("abc"))), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
check("sha256(448 bits)", hex(C.sha256(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))),
  "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
const crypto = require("crypto");
for (const len of [55, 56, 63, 64, 65, 119, 1000]) {
  const data = Buffer.alloc(len, 0x61);
  check(`sha256(${len} bytes)`, hex(C.sha256(new Uint8Array(data))), crypto.createHash("sha256").update(data).digest("hex"));
}

// RFC 4231 HMAC-SHA256 test cases 1, 2, 4 and 6 (key longer than the block).
check("rfc4231 #1", hex(C.hmac(bytes("0b".repeat(20)), ascii("Hi There"))), "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
check("rfc4231 #2", hex(C.hmac(ascii("Jefe"), ascii("what do ya want for nothing?"))), "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
check("rfc4231 #4", hex(C.hmac(bytes("0102030405060708090a0b0c0d0e0f10111213141516171819"), bytes("cd".repeat(50)))),
  "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b");
check("rfc4231 #6", hex(C.hmac(bytes("aa".repeat(131)), ascii("Test Using Larger Than Block-Size Key - Hash Key First"))),
  "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54");

// The request signature, identical to PhoneAuth.sign in Kotlin and to Python's hmac.
const key = Uint8Array.from({ length: 32 }, (_, i) => i);
check("request signature", C.sign(key, "POST", "/api/set", 1800000000000, "abcdefghijklmnop", '{"edge":"top","note":"é"}'),
  "27a78c2ef6577857e1d9d314ed1c89db92358a901f186a2bf6dac07d49e4e28e");

// The pairing key travels base64url-encoded without padding.
check("base64url", hex(C.fromBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")), hex(key));

console.log(`phone page crypto: ${n} checks passed`);
