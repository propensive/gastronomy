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

import anticipation.*
import symbolism.*
import turbulence.*

infix type of [Type <: { type Of }, OfType] = Type { type Of = OfType }

package hashFunctions:
  given HashFunction of Crc32 as crc32 = Crc32.hashFunction
  given HashFunction of Md5 as md5 = Md5.hashFunction
  given HashFunction of Sha1 as sha1 = Sha1.hashFunction

  given [BitsType <: 224 | 256 | 384 | 512: ValueOf] => HashFunction of Sha2[BitsType] as sha2 =
    Sha2.hashFunction[BitsType]

extension [ValueType: Digestible](value: ValueType)
  def digest[HashType <: Algorithm](using HashFunction of HashType): Digest of HashType =
    val digester = Digester(ValueType.digest(_, value))
    digester.apply

extension [SourceType: Readable by Bytes](source: SourceType)
  def checksum[HashType <: Algorithm](using HashFunction of HashType): Digest of HashType =
    source.stream[Bytes].digest[HashType]
