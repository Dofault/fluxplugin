package fr.influencraft.fluxmigration;

/**
 * One data migration, run once (its id is recorded in Flux's database when it succeeds).
 *
 * <p>Rules (see plugins/fluxplugin/CLAUDE.md, « Migrations and patches »):</p>
 * <ul>
 *   <li>id = {@code yyyy-MM-dd_short_name}, never changed once deployed;</li>
 *   <li>idempotent: running it twice must not duplicate anything (skip what the target already has);</li>
 *   <li>never deletes the source data: archiving the former plugin is a manual step of the go-live list;</li>
 *   <li>talks to the target plugins only through their public API ({@code <plugin>.api}).</li>
 * </ul>
 */
public interface Migration {

    String id();

    String description();

    /** Whether it can run now: source data present and target plugins loaded. */
    boolean ready();

    /** Runs it; returns a one-line report (counts). Throws to be retried at the next start. */
    String run() throws Exception;
}
