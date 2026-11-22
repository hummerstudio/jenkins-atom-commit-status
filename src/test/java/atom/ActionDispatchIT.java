package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code action} switch itself: which of the two reporting APIs a run reaches.
 *
 * <p>{@code status} is the default and the behaviour the atom had before the checks atom was merged into it, so a run
 * with no {@code action} at all has to stay on the statuses API; {@code checks} is the other one; anything else is a
 * user error that names both values instead of silently picking one. The two paths' own contracts are covered by
 * {@link CommitStatusIT} and by {@link GithubChecksIT} / {@link GithubChecksContractIT} / {@link GithubChecksExtraIT}.
 */
class ActionDispatchIT {

    /** Both APIs want a token, and each says so in its own words — that is enough to see which one ran. */
    @Test
    @DisplayName("with no action the atom stays on the statuses API: the default is status, not checks")
    void defaultsToStatus() throws Exception {
        AtomResult run = Atom.of()
                .input("apiUrl", "http://127.0.0.1:1")
                .input("account", "acme")
                .input("repo", "parcel")
                .input("sha", "abcdef1")
                .run();

        run.assertRefused();
        assertEquals("40002", run.field("errorCode"), run.toString());
        assertTrue(run.field("message").contains("anonymous status"),
                "the status path's own wording is missing, so this was not the status action: " + run.field("message"));
        assertFalse(run.field("message").contains("checks API"),
                "a run without action went to the checks action: " + run.field("message"));
    }

    /** The same inputs, with action spelled out, still reach the statuses API. */
    @Test
    @DisplayName("action=status is accepted as the default it is")
    void acceptsStatusExplicitly() throws Exception {
        AtomResult run = Atom.of()
                .input("action", "status")
                .input("apiUrl", "http://127.0.0.1:1")
                .input("account", "acme")
                .input("repo", "parcel")
                .input("sha", "abcdef1")
                .run();

        run.assertRefused();
        assertEquals("40002", run.field("errorCode"), run.toString());
        assertTrue(run.field("message").contains("anonymous status"), run.field("message"));
    }

    /** action=checks reaches the checks API, whose refusal is worded differently. */
    @Test
    @DisplayName("action=checks reaches the checks API, and the run says which one it is")
    void checksIsNotTheStatusPath() throws Exception {
        AtomResult run = Atom.of()
                .input("action", "checks")
                .input("apiUrl", "http://127.0.0.1:1")
                .input("account", "acme")
                .input("repo", "parcel")
                .input("sha", "abcdef1")
                .run();

        run.assertRefused();
        assertEquals("40002", run.field("errorCode"), run.toString());
        assertTrue(run.field("message").contains("checks API refuses anonymous writes"),
                "the checks path's own wording is missing: " + run.field("message"));
    }

    /** Nothing is guessed: an action neither of them is has to name both values that exist. */
    @Test
    @DisplayName("an unknown action is a user error that names status and checks")
    void unknownActionNamesBothValues() throws Exception {
        for (String action : new String[] {"verify", "statuses", "check"}) {
            AtomResult run = Atom.of()
                    .input("action", action)
                    .input("apiUrl", "http://127.0.0.1:1")
                    .input("account", "acme")
                    .input("repo", "parcel")
                    .input("sha", "abcdef1")
                    .run();

            run.assertRefused();
            assertEquals("40001", run.field("errorCode"), action + ": " + run.toString());
            assertTrue(run.field("message").contains("'" + action + "'"),
                    action + ": the message does not quote the value it got: " + run.field("message"));
            assertTrue(run.field("message").contains("status") && run.field("message").contains("checks"),
                    action + ": the message does not name both values: " + run.field("message"));
        }
    }
}
