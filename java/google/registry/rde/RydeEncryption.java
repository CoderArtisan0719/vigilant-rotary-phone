// Copyright 2018 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.rde;

import static com.google.common.base.Preconditions.checkArgument;
import static org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags.AES_128;
import static org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME;

import google.registry.util.ImprovedInputStream;
import google.registry.util.ImprovedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.CheckReturnValue;
import javax.annotation.WillNotClose;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;

/**
 * Input/Output Streams for encryption and decryption of RyDE and GhostRyDE files.
 *
 * <p>This uses 128-bit AES (Rijndael) as the symmetric encryption algorithm. This is the only key
 * strength ICANN allows. The other valid algorithms are TripleDES and CAST5 per RFC 4880. It's
 * probably for the best that we're not using AES-256 since it's been weakened over the years to
 * potentially being worse than AES-128.
 *
 * <p>The key for the symmetric algorithm is generated by a random number generator which SHOULD
 * come from {@code /dev/random} (see: {@link sun.security.provider.NativePRNG}) but Java doesn't
 * offer any guarantees that {@link SecureRandom} isn't pseudo-random.
 *
 * <p>The asymmetric algorithm is whatever one is associated with the {@link PGPPublicKey} object
 * you provide. That should be either RSA or DSA, per the ICANN escrow spec. The underlying {@link
 * PGPEncryptedDataGenerator} class uses PGP Cipher Feedback Mode to chain blocks. No integrity
 * packet is used.
 *
 * @see <a href="http://tools.ietf.org/html/rfc4880">RFC 4880 (OpenPGP Message Format)</a>
 * @see <a href="http://en.wikipedia.org/wiki/Advanced_Encryption_Standard">AES (Wikipedia)</a>
 */
final class RydeEncryption {

  private static final int BUFFER_SIZE = 64 * 1024;

  /**
   * The symmetric encryption algorithm to use. Do not change this value without checking the RFCs
   * to make sure the encryption algorithm and strength combination is allowed.
   *
   * @see org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
   */
  private static final int CIPHER = AES_128;

  /**
   * This option adds an additional checksum to the OpenPGP message. From what I can tell, this is
   * meant to fix a bug that made a certain type of message tampering possible. GPG will actually
   * complain on the command line when decrypting a message without this feature.
   *
   * <p>However I'm reasonably certain that this is not required if you have a signature (and
   * remember to use it!) and the ICANN requirements document do not mention this. So we're going to
   * leave it out.
   */
  static final boolean RYDE_USE_INTEGRITY_PACKET = false;

  /**
   * Unlike Ryde, we're going to enable the integrity packet because it makes GnuPG happy. It's also
   * probably necessary to prevent tampering since we don't sign ghostryde files.
   */
  static final boolean GHOSTRYDE_USE_INTEGRITY_PACKET = true;

  /**
   * The source of random bits. This should not be changed at Google because it uses dev random in
   * production, and the testing environment is configured to make this go fast and not drain system
   * entropy.
   *
   * @see SecureRandom#getInstance(String)
   */
  private static final String RANDOM_SOURCE = "NativePRNG";

  /**
   * Creates an OutputStream that encrypts data for the owners of {@code receiverKeys}.
   *
   * <p>TODO(b/110465964): document where the input comes from / output goes to. Something like
   * documenting that os is the upstream OutputStream and the result goes into openCompressor.
   *
   * @param os where to write the encrypted data. Is not closed by this object.
   * @param withIntegrityPacket whether to add the integrity packet to the encrypted data. Not
   *     allowed in RyDE.
   * @param receiverKeys at least one encryption key. The message will be decryptable with any of
   *     the given keys.
   * @throws IllegalArgumentException if {@code publicKey} is invalid
   * @throws RuntimeException to rethrow {@link PGPException} and {@link IOException}
   */
  @CheckReturnValue
  static ImprovedOutputStream openEncryptor(
      @WillNotClose OutputStream os,
      boolean withIntegrityPacket,
      Collection<PGPPublicKey> receiverKeys) {
    try {
      PGPEncryptedDataGenerator encryptor =
          new PGPEncryptedDataGenerator(
              new JcePGPDataEncryptorBuilder(CIPHER)
                  .setWithIntegrityPacket(withIntegrityPacket)
                  .setSecureRandom(SecureRandom.getInstance(RANDOM_SOURCE))
                  .setProvider(PROVIDER_NAME));
      checkArgument(!receiverKeys.isEmpty(), "Must give at least one receiver key");
      receiverKeys.forEach(
          key -> encryptor.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(key)));
      return new ImprovedOutputStream("RydeEncryptor", encryptor.open(os, new byte[BUFFER_SIZE]));
    } catch (NoSuchAlgorithmException e) {
      throw new ProviderException(e);
    } catch (IOException | PGPException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Creates an InputStream that encrypts data for the owners of {@code receiverKeys}.
   *
   * <p>TODO(b/110465964): document where the input comes from / output goes to. Something like
   * documenting that input is upstream InputStream and the result goes into openDecompressor.
   *
   * @param input from where to read the encrypted data. Is not closed by this object.
   * @param checkIntegrityPacket whether to check the integrity packet on the encrypted data. Only
   *     use if the integrety packet was created when encrypting.
   * @param privateKey the private counterpart of one of the receiverKeys used to encrypt.
   * @throws IllegalArgumentException if {@code publicKey} is invalid
   * @throws RuntimeException to rethrow {@link PGPException} and {@link IOException}
   */
  @CheckReturnValue
  static ImprovedInputStream openDecryptor(
      @WillNotClose InputStream input, boolean checkIntegrityPacket, PGPPrivateKey privateKey) {
    try {
      PGPEncryptedDataList ciphertextList =
          PgpUtils.readSinglePgpObject(input, PGPEncryptedDataList.class);
      // Go over all the possible decryption keys, and look for the one that has our key ID.
      Optional<PGPPublicKeyEncryptedData> cyphertext =
          PgpUtils.stream(ciphertextList, PGPPublicKeyEncryptedData.class)
              .filter(ciphertext -> ciphertext.getKeyID() == privateKey.getKeyID())
              .findAny();
      // If we can't find one with our key ID, then we can't decrypt the file!
      if (!cyphertext.isPresent()) {
        String keyIds =
            PgpUtils.stream(ciphertextList, PGPPublicKeyEncryptedData.class)
                .map(ciphertext -> Long.toHexString(ciphertext.getKeyID()))
                .collect(Collectors.joining(","));
        throw new PGPException(
            String.format(
                "Message was encrypted for keyids [%s] but ours is %x",
                keyIds, privateKey.getKeyID()));
      }

      InputStream dataStream =
          cyphertext.get().getDataStream(
              new JcePublicKeyDataDecryptorFactoryBuilder()
                  .setProvider(PROVIDER_NAME)
                  .build(privateKey));
      if (!checkIntegrityPacket) {
        return new ImprovedInputStream("RydeDecryptor", dataStream);
      }
      // We want an input stream that also verifies ciphertext wasn't corrupted or tampered with
      // when the stream is closed.
      return new ImprovedInputStream("RydeDecryptor", dataStream) {
        @Override
        protected void onClose() throws IOException {
          try {
            if (!cyphertext.get().verify()) {
              throw new PGPException("integrity check failed: possible tampering D:");
            }
          } catch (PGPException e) {
            throw new IllegalStateException(e);
          }
        }
      };
    } catch (PGPException e) {
      throw new RuntimeException(e);
    }
  }

  private RydeEncryption() {}
}
