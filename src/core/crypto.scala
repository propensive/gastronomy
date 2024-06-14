/*
    Gastronomy, version [unreleased]. Copyright 2024 Jon Pretty, Propensive OÜ.

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

import javax.crypto as jc, javax.crypto.spec.*
import java.security as js, js.spec.*

import rudiments.*
import vacuous.*
import fulminate.*
import gossamer.*
import contingency.*
import spectacular.*
import anticipation.*
import hieroglyph.*

import java.nio.*, charset.*

trait Cipher:
  type Size <: Nat
  def keySize: Size
  def privateToPublic(key: Bytes): Bytes
  def genKey(): Bytes

trait Encryption:
  def encrypt(value: Bytes, privateKey: Bytes): Bytes
  def decrypt(message: Bytes, publicKey: Bytes): Bytes

trait Signing:
  def sign(data: Bytes, privateKey: Bytes): Bytes
  def verify(data: Bytes, signature: Bytes, publicKey: Bytes): Boolean

trait Symmetric

object MessageData:
  given [MessageType <: Cipher] => MessageData[MessageType] is Showable = msg => t"MessageData(${msg.bytes.encodeAs[Base64]})"

case class MessageData[+AlgorithmType <: Cipher](bytes: Bytes)

object Signature:
  given [SignatureType <: Cipher] => Signature[SignatureType] is Showable = sig => t"Signature(${sig.bytes.encodeAs[Base64]})"

case class Signature[+AlgorithmType <: Cipher](bytes: Bytes)

object ExposeSecretKey

object PublicKey:
  given [KeyType <: Cipher](using HexAlphabet) => PublicKey[KeyType] is Showable = key => t"PublicKey(${key.bytes.encodeAs[Hex]})"

case class PublicKey[AlgorithmType <: Cipher](bytes: Bytes):
  def encrypt[ValueType](value: ValueType)
      (using algorithm: AlgorithmType & Encryption, codec: ByteCodec[ValueType])
          : MessageData[AlgorithmType] =

    MessageData(algorithm.encrypt(codec.encode(value), bytes))

  def verify[ValueType: ByteCodec](value: ValueType, signature: Signature[AlgorithmType])
      (using codec: ByteCodec[ValueType], algorithm: AlgorithmType & Signing)
          : Boolean =

    algorithm.verify(codec.encode(value), signature.bytes, bytes)

  def pem: Pem = Pem(t"PUBLIC KEY", bytes)

object PrivateKey:
  def generate[AlgorithmType <: Cipher]()(using AlgorithmType): PrivateKey[AlgorithmType] =
    PrivateKey(summon[AlgorithmType].genKey())

  // given [KeyType <: Cipher] => PrivateKey[KeyType] is Showable =
  //   key => t"PrivateKey(${key.privateBytes.digest[Sha2[256]].encodeAs[Base64]})"

case class PrivateKey[AlgorithmType <: Cipher](private[gastronomy] val privateBytes: Bytes):
  def public(using AlgorithmType): PublicKey[AlgorithmType] =
    PublicKey(summon[AlgorithmType].privateToPublic(privateBytes))

  inline def decrypt[ValueType: ByteCodec](message: MessageData[AlgorithmType])
      (using AlgorithmType & Encryption, Errant[CryptoError])
          : ValueType =

    decrypt(message.bytes)

  inline def decrypt[ValueType: ByteCodec](bytes: Bytes)
      (using AlgorithmType & Encryption, Errant[CryptoError])
          : ValueType =

    summon[ByteCodec[ValueType]].decode(summon[AlgorithmType].decrypt(bytes, privateBytes))

  inline def sign[ValueType: ByteCodec](value: ValueType)(using AlgorithmType & Signing)
          : Signature[AlgorithmType] =

    Signature(summon[AlgorithmType].sign(summon[ByteCodec[ValueType]].encode(value), privateBytes))

  def pem(reveal: ExposeSecretKey.type): Pem = Pem(t"PRIVATE KEY", privateBytes)

object SymmetricKey:

  def generate[AlgorithmType <: Cipher & Symmetric]()(using AlgorithmType)
          : SymmetricKey[AlgorithmType] =

    SymmetricKey(summon[AlgorithmType].genKey())

class SymmetricKey[AlgorithmType <: Cipher](private[gastronomy] val bytes: Bytes)
extends PrivateKey[AlgorithmType](bytes):
  def encrypt[ValueType: ByteCodec](value: ValueType)(using AlgorithmType & Encryption)
          : MessageData[AlgorithmType] =

    public.encrypt(value)

  def verify[ValueType: ByteCodec](value: ValueType, signature: Signature[AlgorithmType])
      (using AlgorithmType & Signing)
          : Boolean =

    public.verify(value, signature)

case class CryptoError(detail: Text) extends Error(msg"could not decode the encrypted data: $detail")

trait ByteCodec[ValueType]:
  def encode(value: ValueType): Bytes
  def decode(bytes: Bytes)(using Errant[CryptoError]): ValueType

object ByteCodec:
  given ByteCodec[Bytes] with
    def encode(value: Bytes): Bytes = value
    def decode(bytes: Bytes)(using Errant[CryptoError]): Bytes = bytes

  given (using CharDecoder, CharEncoder): ByteCodec[Text] with
    def encode(value: Text): Bytes = value.bytes

    def decode(bytes: Bytes)(using Errant[CryptoError]): Text =
      val buffer = ByteBuffer.wrap(bytes.mutable(using Unsafe))

      try Charset.forName("UTF-8").nn.newDecoder().nn.decode(buffer).toString.show
      catch CharacterCodingException =>
        abort(CryptoError(t"the message did not contain a valid UTF-8 string"))

object Aes:
  given aes[BitsType <: 128 | 192 | 256: ValueOf]: Aes[BitsType] = Aes()

object Rsa:
  given rsa[I <: 1024 | 2048: ValueOf]: Rsa[I] = Rsa()

object Dsa:
  given dsa[BitsType <: 512 | 1024 | 2048 | 3072: ValueOf]: Dsa[BitsType] = Dsa()

class Aes[BitsType <: 128 | 192 | 256: ValueOf]() extends Cipher, Encryption, Symmetric:
  type Size = BitsType
  def keySize: BitsType = valueOf[BitsType]
  private def init() = jc.Cipher.getInstance("AES/ECB/PKCS5Padding")
  private def makeKey(key: Bytes): SecretKeySpec = SecretKeySpec(key.mutable(using Unsafe), "AES")

  def encrypt(message: Bytes, key: Bytes): Bytes =
    val cipher = init().nn
    cipher.init(jc.Cipher.ENCRYPT_MODE, makeKey(key))
    cipher.doFinal(message.mutable(using Unsafe)).nn.immutable(using Unsafe)

  def decrypt(message: Bytes, key: Bytes): Bytes =
    val cipher = init().nn
    cipher.init(jc.Cipher.DECRYPT_MODE, makeKey(key))
    cipher.doFinal(message.mutable(using Unsafe)).nn.immutable(using Unsafe)

  def genKey(): Bytes =
    val keyGen = jc.KeyGenerator.getInstance("AES").nn
    keyGen.init(keySize)

    keyGen.generateKey().nn.getEncoded.nn.immutable(using Unsafe)

  def privateToPublic(key: Bytes): Bytes = key

class Rsa[BitsType <: 1024 | 2048: ValueOf]() extends Cipher, Encryption:
  type Size = BitsType
  def keySize: BitsType = valueOf[BitsType]

  def privateToPublic(bytes: Bytes): Bytes =
    val privateKey = keyFactory().generatePrivate(PKCS8EncodedKeySpec(bytes.mutable(using Unsafe))).nn match
      case key: js.interfaces.RSAPrivateCrtKey => key
      case key: js.PrivateKey                  => throw Panic(msg"public key did not have the correct type")

    val spec = RSAPublicKeySpec(privateKey.getModulus, privateKey.getPublicExponent)
    keyFactory().generatePublic(spec).nn.getEncoded.nn.immutable(using Unsafe)

  def decrypt(message: Bytes, key: Bytes): Bytes =
    val cipher = init().nn
    val privateKey = keyFactory().generatePrivate(PKCS8EncodedKeySpec(key.mutable(using Unsafe)))
    cipher.init(jc.Cipher.DECRYPT_MODE, privateKey)
    cipher.doFinal(message.mutable(using Unsafe)).nn.immutable(using Unsafe)

  def encrypt(message: Bytes, key: Bytes): Bytes =
    val cipher = init().nn
    val publicKey = keyFactory().generatePublic(X509EncodedKeySpec(key.mutable(using Unsafe)))
    cipher.init(jc.Cipher.ENCRYPT_MODE, publicKey)
    cipher.doFinal(message.mutable(using Unsafe)).nn.immutable(using Unsafe)

  def genKey(): Bytes =
    val generator = js.KeyPairGenerator.getInstance("RSA").nn
    generator.initialize(keySize)
    val keyPair = generator.generateKeyPair().nn
    keyPair.getPrivate.nn.getEncoded.nn.immutable(using Unsafe)

  private def init(): jc.Cipher = jc.Cipher.getInstance("RSA").nn
  private def keyFactory(): js.KeyFactory = js.KeyFactory.getInstance("RSA").nn

class Dsa[BitsType <: 512 | 1024 | 2048 | 3072: ValueOf]() extends Cipher, Signing:
  type Size = BitsType
  def keySize: BitsType = valueOf[BitsType]

  def genKey(): Bytes =
    val generator = js.KeyPairGenerator.getInstance("DSA").nn
    val random = js.SecureRandom()
    generator.initialize(keySize, random)
    val keyPair = generator.generateKeyPair().nn

    val pubKey = keyPair.getPublic.nn match
      case key: js.interfaces.DSAPublicKey => key
      case key: js.PublicKey               => throw Panic(msg"public key did not have the correct type")

    keyPair.getPrivate.nn.getEncoded.nn.immutable(using Unsafe)

  def sign(data: Bytes, keyBytes: Bytes): Bytes =
    val sig = init()
    val key = keyFactory().generatePrivate(PKCS8EncodedKeySpec(keyBytes.to(Array)))
    sig.initSign(key)
    sig.update(data.to(Array))
    sig.sign().nn.immutable(using Unsafe)

  def verify(data: Bytes, signature: Bytes, keyBytes: Bytes): Boolean =
    val sig = init()
    val key = keyFactory().generatePublic(X509EncodedKeySpec(keyBytes.to(Array)))
    sig.initVerify(key)
    sig.update(data.to(Array))
    sig.verify(signature.to(Array))

  def privateToPublic(keyBytes: Bytes): Bytes =
    val key = keyFactory().generatePrivate(PKCS8EncodedKeySpec(keyBytes.to(Array))).nn match
      case key: js.interfaces.DSAPrivateKey => key
      case key: js.PrivateKey               => throw Panic(msg"private key did not have the correct type")

    val params = key.getParams.nn
    val y = params.getG.nn.modPow(key.getX, params.getP.nn)
    val spec = DSAPublicKeySpec(y, params.getP, params.getQ, params.getG)
    keyFactory().generatePublic(spec).nn.getEncoded.nn.immutable(using Unsafe)

  private def init(): js.Signature = js.Signature.getInstance("DSA").nn
  private def keyFactory(): js.KeyFactory = js.KeyFactory.getInstance("DSA").nn

case class PemError(detail: Text) extends Error(msg"could not parse PEM-encoded content: $detail")

object Feistel:
  def apply(subkeys: List[Int], round: (Int, Int) => Int)(input: Long): Long =
    def recur(value: Long, subkeys: List[Int]): Long =
      subkeys match
        case Nil => value
        case head :: tail =>
          val left: Int = (value >> 32).toInt
          val right: Int = value.toInt
          recur((right.toLong << 32) | (left ^ round(right, head)).toLong, tail)

    recur(input, subkeys)
