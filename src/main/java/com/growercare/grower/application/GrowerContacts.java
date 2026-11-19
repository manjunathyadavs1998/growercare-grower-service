package com.growercare.grower.application;

import com.growercare.common.error.NotFoundException;
import com.growercare.grower.application.port.GrowerRepository;
import com.growercare.grower.domain.PhoneProtector;
import com.growercare.shard.ShardRouter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SPEC-GRW-07 R320/R321: a sender (notification-service) reads an ACTIVE grower's phone and language. The phone is
 * decrypted only here, every read is audited (D2), and the number is never logged or kept.
 */
@Service
public class GrowerContacts {

    /** The decrypted contact; toString hides the number. */
    public record Contact(String phone, String language, String status) {
        @Override
        public String toString() {
            return "Contact[phone=***, language=" + language + ", status=" + status + "]";
        }
    }

    private final GrowerRepository growers;
    private final ShardRouter router;
    private final PhoneProtector phones;
    private final TransactionTemplate tx;

    public GrowerContacts(GrowerRepository growers, ShardRouter router, PhoneProtector phones, TransactionTemplate tx) {
        this.growers = growers;
        this.router = router;
        this.phones = phones;
        this.tx = tx;
    }

    public Contact contact(long growerId, String clientId) {
        var sealed = router.forId(growerId)
                .flatMap(shard -> growers.contact(shard, growerId))
                .filter(c -> "ACTIVE".equals(c.status()))
                .orElseThrow(() -> new NotFoundException("Grower"));
        var phone = phones.decrypt(new PhoneProtector.Sealed(sealed.phoneCiphertext(), sealed.keyId()));
        tx.executeWithoutResult(status -> growers.auditContactRead(growerId, clientId));
        return new Contact(phone, sealed.language(), sealed.status());
    }
}
