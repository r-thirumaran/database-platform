package org.dbplatform.controlplane.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.dbplatform.controlplane.api.dto.CredentialRequest;
import org.dbplatform.controlplane.api.error.ApiException;
import org.dbplatform.controlplane.domain.Credential;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Ids;
import org.dbplatform.controlplane.repo.CredentialRepository;
import org.dbplatform.controlplane.repo.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CredentialService {
    private static final Logger audit = LoggerFactory.getLogger("org.dbplatform.controlplane.audit.credentials");

    private final CredentialRepository credentials;
    private final DatabaseRepository databases;
    private final SecretCipher cipher;
    private final ConfigVersionService configVersion;

    public CredentialService(CredentialRepository credentials, DatabaseRepository databases, SecretCipher cipher, ConfigVersionService configVersion) {
        this.credentials = credentials; this.databases = databases; this.cipher = cipher; this.configVersion = configVersion;
    }

    @Transactional(readOnly = true)
    public List<Credential> list() { return credentials.findAllByOrderByNameAsc(); }

    @Transactional(readOnly = true)
    public Credential get(String id) { return credentials.findById(id).orElseThrow(() -> new ApiException.NotFound("Credential", id)); }

    public Credential create(CredentialRequest in) {
        credentials.findByName(in.name()).ifPresent(c -> { throw new ApiException.Conflict("Credential '" + in.name() + "' already exists"); });
        Credential c = new Credential();
        c.setId(Ids.newId());
        c.setCreatedAt(Instant.now());
        c.setRotatedAt(Instant.now());
        copy(in, c, true);
        configVersion.bump();
        return credentials.save(c);
    }

    public Credential update(String id, CredentialRequest in) {
        Credential c = get(id);
        credentials.findByName(in.name()).filter(o -> !o.getId().equals(id))
                .ifPresent(o -> { throw new ApiException.Conflict("Credential '" + in.name() + "' already exists"); });
        copy(in, c, false);
        configVersion.bump();
        return credentials.save(c);
    }

    public Credential upsertByName(CredentialRequest in) {
        return credentials.findByName(in.name()).map(e -> update(e.getId(), in)).orElseGet(() -> create(in));
    }

    public Credential rotate(String id, String newSecret) {
        Credential c = get(id);
        if (c.getProvider() == Enums.CredentialProvider.INLINE) {
            if (newSecret == null || newSecret.isEmpty()) throw new ApiException.BadRequest("INLINE credentials need a 'secret' to rotate");
            c.setEncryptedSecret(cipher.encrypt(newSecret));
        }
        c.setVersion(c.getVersion() + 1);
        c.setRotatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        configVersion.bump();
        return credentials.save(c);
    }

    public void delete(String id) {
        Credential c = get(id);
        if (databases.countByCredentialId(id) > 0) throw new ApiException.Conflict("Credential '" + c.getName() + "' is referenced by a database");
        credentials.delete(c);
        configVersion.bump();
    }

    /** Secret material; INLINE is decrypted, ENV/FILE are read, other providers are not implemented in the POC. */
    public record Material(String username, String secret, long version) {}

    @Transactional(readOnly = true)
    public Material material(String id, String requester) {
        Credential c = get(id);
        audit.info("credential material requested credentialId={} name={} provider={} by={}", c.getId(), c.getName(), c.getProvider(), requester);
        String secret = switch (c.getProvider()) {
            case INLINE -> {
                if (c.getEncryptedSecret() == null) throw new ApiException.Conflict("Credential '" + c.getName() + "' has no secret stored");
                yield cipher.decrypt(c.getEncryptedSecret());
            }
            case ENV -> {
                String v = c.getRef() == null ? null : System.getenv(c.getRef());
                if (v == null) throw new ApiException.Conflict("Environment variable '" + c.getRef() + "' for credential '" + c.getName() + "' is not set on the control plane");
                yield v;
            }
            case FILE -> {
                try {
                    yield Files.readString(Path.of(c.getRef()), StandardCharsets.UTF_8).strip();
                } catch (IOException | RuntimeException e) {
                    throw new ApiException.Conflict("Cannot read secret file '" + c.getRef() + "' for credential '" + c.getName() + "': " + e.getMessage());
                }
            }
            default -> throw new ApiException.NotImplemented("Credential provider " + c.getProvider()
                    + " is not implemented in this POC; use INLINE, ENV or FILE (or run a sidecar that exposes the secret as an env var/file)");
        };
        return new Material(c.getUsername(), secret, c.getVersion());
    }

    private void copy(CredentialRequest in, Credential c, boolean creating) {
        c.setName(in.name());
        c.setUsername(in.username());
        Enums.CredentialProvider provider = in.provider() == null ? Enums.CredentialProvider.INLINE : in.provider();
        c.setProvider(provider);
        c.setRef(provider == Enums.CredentialProvider.INLINE ? null : in.ref());
        if (provider != Enums.CredentialProvider.INLINE && (in.ref() == null || in.ref().isBlank())) {
            throw new ApiException.BadRequest("Credential provider " + provider + " requires a 'ref'");
        }
        if (provider == Enums.CredentialProvider.INLINE) {
            if (in.secret() != null && !in.secret().isEmpty()) {
                c.setEncryptedSecret(cipher.encrypt(in.secret()));
                if (!creating) { c.setVersion(c.getVersion() + 1); c.setRotatedAt(Instant.now()); }
            } else if (creating) {
                c.setEncryptedSecret(null);
            }
        } else {
            c.setEncryptedSecret(null);
        }
        c.setDescription(in.description());
        c.setUpdatedAt(Instant.now());
    }
}
