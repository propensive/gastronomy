/*
    Gastronomy, version 0.5.0. Copyright 2018-21 Jon Pretty, Propensive OÜ.

    The primary distribution site is: https://propensive.com/

    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
    file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
    either express or implied. See the License for the specific language governing permissions
    and limitations under the License.
*/

package gastronomy

import probably.*
import rudiments.*
import gossamer.*

object Tests extends Suite("Gastronomy tests"):
 
  val request: String = """
    |-----BEGIN CERTIFICATE REQUEST-----
    |MIIB9TCCAWACAQAwgbgxGTAXBgNVBAoMEFF1b1ZhZGlzIExpbWl0ZWQxHDAaBgNV
    |BAsME0RvY3VtZW50IERlcGFydG1lbnQxOTA3BgNVBAMMMFdoeSBhcmUgeW91IGRl
    |Y29kaW5nIG1lPyAgVGhpcyBpcyBvbmx5IGEgdGVzdCEhITERMA8GA1UEBwwISGFt
    |aWx0b24xETAPBgNVBAgMCFBlbWJyb2tlMQswCQYDVQQGEwJCTTEPMA0GCSqGSIb3
    |DQEJARYAMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCJ9WRanG/fUvcfKiGl
    |EL4aRLjGt537mZ28UU9/3eiJeJznNSOuNLnF+hmabAu7H0LT4K7EdqfF+XUZW/2j
    |RKRYcvOUDGF9A7OjW7UfKk1In3+6QDCi7X34RE161jqoaJjrm/T18TOKcgkkhRzE
    |apQnIDm0Ea/HVzX/PiSOGuertwIDAQABMAsGCSqGSIb3DQEBBQOBgQBzMJdAV4QP
    |Awel8LzGx5uMOshezF/KfP67wJ93UW+N7zXY6AwPgoLj4Kjw+WtU684JL8Dtr9FX
    |ozakE+8p06BpxegR4BR3FMHf6p+0jQxUEAkAyb/mVgm66TyghDGC6/YkiKoZptXQ
    |98TwDIK/39WEB/V607As+KoYazQG8drorw==
    |-----END CERTIFICATE REQUEST-----
    """.stripMargin

  val pangram: String = "The quick brown fox jumps over the lazy dog"

  def run(using Runner): Unit = {
    test("Sha256, Hex") {
      "Hello world".digest[Sha2[256]].encode[Hex]
    }.assert(_ == "64EC88CA00B268E5BA1A35678A1B5316D212F4F366B2477232534A8AECA37F3C")

    test("Md5, Base64") {
      "Hello world".digest[Md5].encode[Base64]
    }.assert(_ == "PiWWCnnbxptnTNTsZ6csYg==")

    test("Sha1, Base64Url") {
      "Hello world".digest[Sha1].encode[Base64Url]
    }.assert(_ == "e1AsOh9IyGCa4hLN-2Od7jlnP14")

    test("Sha384, Base64") {
      "Hello world".digest[Sha2[384]].encode[Base64]
    }.assert(_ == "kgOwxEOf0eauWHiGYze3xTKs1tkmAVDIAxjoq4wnzjMBifjflPuJDfHSmP82Bifh")
    
    test("Sha512, Base64") {
      "Hello world".digest[Sha2[512]].encode[Base64]
    }.assert(_ == "t/eDuu2Cl/DbkXRiGE/08I5pwtXl95qUJgD5cl9Yzh8pwYE5v4CwbA//K900c4RS7PQMSIwip+PYDN9"+
        "vnBwNRw==")

    test("Encode to Binary") {
      IArray[Byte](1, 2, 3, 4).encode[Binary]
    }.assert(_ == "00000001000000100000001100000100")

    test("Extract PEM message type") {
      val example = """
        |-----BEGIN EXAMPLE-----
        |MIIB9TCCAWACAQAwgbgxGTAXBgNVBAoMEFF1b1ZhZGlzIExpbWl0ZWQxHDAaBgNV
        |-----END EXAMPLE-----
        """.stripMargin
      
      Pem.parse(example).kind
    }.assert(_ == "EXAMPLE")

    test("Decode PEM certificate") {
      Pem.parse(request).data.digest[Md5].encode[Base64]
    }.assert(_ == "iMwRdyDFStqq08vqjPbzYw==")
  
    test("PEM roundtrip") {
      Pem.parse(request).serialize
    }.assert(_ == request.trim)

    test("RSA roundtrip") {
      val privateKey: PrivateKey[Rsa[1024]] = PrivateKey.generate[Rsa[1024]]()
      val message: Message[Rsa[1024]] = privateKey.public.encrypt("Hello world")
      privateKey.decrypt[String](message.bytes)
    }.assert(_ == "Hello world")
    
    test("AES roundtrip") {
      val key: SymmetricKey[Aes[256]] = SymmetricKey.generate[Aes[256]]()
      val message = key.encrypt("Hello world")
      key.decrypt[String](message.bytes)
    }.assert(_ == "Hello world")

    test("Sign some data with DSA") {
      val privateKey: PrivateKey[Dsa[1024]] = PrivateKey.generate[Dsa[1024]]()
      val message = "Hello world"
      val signature = privateKey.sign(message)
      privateKey.public.verify(message, signature)
    }.assert(identity)

    test("Check bad signature") {
      val privateKey: PrivateKey[Dsa[1024]] = PrivateKey.generate[Dsa[1024]]()
      val message = "Hello world"
      val signature = privateKey.sign("Something else")
      privateKey.public.verify(message, signature)
    }.assert(!identity(_))

    test("MD5 HMAC") {
      pangram.hmac[Md5]("key".bytes).encode[Hex]
    }.assert(_ == "80070713463E7749B90C2DC24911E275")
    
    test("SHA1 HMAC") {
      pangram.hmac[Sha1]("key".bytes).encode[Hex]
    }.assert(_ == "DE7C9B85B8B78AA6BC8A7A36F70A90701C9DB4D9")

    test("SHA256 HMAC") {
      pangram.hmac[Sha2[256]]("key".bytes).encode[Hex]
    }.assert(_ == "F7BC83F430538424B13298E6AA6FB143EF4D59A14946175997479DBC2D1A3CD8")
    
    test("SHA384 HMAC") {
      pangram.hmac[Sha2[384]]("key".bytes).encode[Base64]
    }.assert(_ == "1/RyfiwLOa4PHkDMlvYCQtW3gBhBzqb8WSxdPhrlBwBYKpbPNeHlVJlf5OAzgcI3")
    
    test("SHA512 HMAC") {
      pangram.hmac[Sha2[512]]("key".bytes).encode[Base64]
    }.assert(_ == "tCrwkFe6weLUFwjkipAuCbX/fxKrQopP6GZTxz3SSPuC+UilSfe3kaW0GRXuTR7Dk1NX5OIxclDQNyr"+
        "6Lr7rOg==")
  }