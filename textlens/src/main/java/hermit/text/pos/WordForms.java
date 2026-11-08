package hermit.text.pos;

import hermit.text.model.Tag;
import hermit.text.model.Token.Candidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The word forms stage (milestone A2 of the annotation plan, section 5, stage 3b; section 13): general English word
 * lists, not drawn from any gold set, for a WORD that neither the closed classes nor the domain lexicon knows. The
 * word is read in lower case, with a typographic apostrophe (U+2019) read as {@code '}. The first case that applies
 * gives its candidates, each naming the rule {@link #RULE}:
 * <ol>
 *   <li>the past form of one of {@link #IRREGULAR} that differs from its participle and is no verb's base
 *       ({@code came}, {@code took}): VERB;</li>
 *   <li>the participle of one of {@link #IRREGULAR}, or a past form equal to its participle, that is no verb's base
 *       ({@code taken}, {@code found}): VERB, then ADJ;</li>
 *   <li>the base of one of {@link #IRREGULAR} or a word of {@link #VERBS}, or its {@link #thirdPerson}
 *       ({@code remain}, {@code remains}, {@code goes}): VERB, then NOUN;</li>
 *   <li>a word of {@link #NOUN_LOOKALIKES} ({@code approval}, {@code agent}): NOUN, then ADJ;</li>
 *   <li>a word of {@link #ADJECTIVES} ({@code exact}, {@code neutral} is not one: the suffix rules give it): ADJ, then
 *       NOUN.</li>
 * </ol>
 * Any other word has none (an empty list), and the suffix rules decide. A word with candidates whose first character
 * is upper case and that does not start its sentence also gets PROPN, first, naming the rule {@code capital}.
 */
public final class WordForms {

    /** The rule every candidate of this stage names. */
    public static final String RULE = "word-forms";

    /** An irregular verb: its base, its past form and its past participle. */
    public record Verb(String base, String past, String participle) {}

    /** Irregular verbs, by base. */
    public static final List<Verb> IRREGULAR = List.of(new Verb("arise", "arose", "arisen"),
            new Verb("awake", "awoke", "awoken"), new Verb("be", "was", "been"), new Verb("bear", "bore", "borne"),
            new Verb("beat", "beat", "beaten"), new Verb("become", "became", "become"),
            new Verb("begin", "began", "begun"), new Verb("bend", "bent", "bent"), new Verb("bet", "bet", "bet"),
            new Verb("bind", "bound", "bound"), new Verb("bite", "bit", "bitten"), new Verb("bleed", "bled", "bled"),
            new Verb("blow", "blew", "blown"), new Verb("break", "broke", "broken"), new Verb("breed", "bred", "bred"),
            new Verb("bring", "brought", "brought"), new Verb("build", "built", "built"),
            new Verb("burn", "burnt", "burnt"), new Verb("burst", "burst", "burst"),
            new Verb("buy", "bought", "bought"), new Verb("cast", "cast", "cast"),
            new Verb("catch", "caught", "caught"), new Verb("choose", "chose", "chosen"),
            new Verb("cling", "clung", "clung"), new Verb("come", "came", "come"), new Verb("cost", "cost", "cost"),
            new Verb("creep", "crept", "crept"), new Verb("cut", "cut", "cut"), new Verb("deal", "dealt", "dealt"),
            new Verb("dig", "dug", "dug"), new Verb("do", "did", "done"), new Verb("draw", "drew", "drawn"),
            new Verb("dream", "dreamt", "dreamt"), new Verb("drink", "drank", "drunk"),
            new Verb("drive", "drove", "driven"), new Verb("eat", "ate", "eaten"), new Verb("fall", "fell", "fallen"),
            new Verb("feed", "fed", "fed"), new Verb("feel", "felt", "felt"), new Verb("fight", "fought", "fought"),
            new Verb("find", "found", "found"), new Verb("flee", "fled", "fled"), new Verb("fling", "flung", "flung"),
            new Verb("fly", "flew", "flown"), new Verb("forbid", "forbade", "forbidden"),
            new Verb("forget", "forgot", "forgotten"), new Verb("forgive", "forgave", "forgiven"),
            new Verb("freeze", "froze", "frozen"), new Verb("get", "got", "gotten"), new Verb("give", "gave", "given"),
            new Verb("go", "went", "gone"), new Verb("grind", "ground", "ground"), new Verb("grow", "grew", "grown"),
            new Verb("hang", "hung", "hung"), new Verb("have", "had", "had"), new Verb("hear", "heard", "heard"),
            new Verb("hide", "hid", "hidden"), new Verb("hit", "hit", "hit"), new Verb("hold", "held", "held"),
            new Verb("hurt", "hurt", "hurt"), new Verb("keep", "kept", "kept"), new Verb("know", "knew", "known"),
            new Verb("lay", "laid", "laid"), new Verb("lead", "led", "led"), new Verb("lean", "leant", "leant"),
            new Verb("leap", "leapt", "leapt"), new Verb("learn", "learnt", "learnt"),
            new Verb("leave", "left", "left"), new Verb("lend", "lent", "lent"), new Verb("let", "let", "let"),
            new Verb("lie", "lay", "lain"), new Verb("light", "lit", "lit"), new Verb("lose", "lost", "lost"),
            new Verb("make", "made", "made"), new Verb("mean", "meant", "meant"), new Verb("meet", "met", "met"),
            new Verb("mislead", "misled", "misled"), new Verb("mistake", "mistook", "mistaken"),
            new Verb("overcome", "overcame", "overcome"), new Verb("override", "overrode", "overridden"),
            new Verb("overtake", "overtook", "overtaken"), new Verb("overwrite", "overwrote", "overwritten"),
            new Verb("pay", "paid", "paid"), new Verb("prove", "proved", "proven"), new Verb("put", "put", "put"),
            new Verb("quit", "quit", "quit"), new Verb("read", "read", "read"),
            new Verb("rebuild", "rebuilt", "rebuilt"), new Verb("redo", "redid", "redone"),
            new Verb("rerun", "reran", "rerun"), new Verb("reset", "reset", "reset"),
            new Verb("rewrite", "rewrote", "rewritten"), new Verb("ride", "rode", "ridden"),
            new Verb("ring", "rang", "rung"), new Verb("rise", "rose", "risen"), new Verb("run", "ran", "run"),
            new Verb("say", "said", "said"), new Verb("see", "saw", "seen"), new Verb("seek", "sought", "sought"),
            new Verb("sell", "sold", "sold"), new Verb("send", "sent", "sent"), new Verb("set", "set", "set"),
            new Verb("shake", "shook", "shaken"), new Verb("shed", "shed", "shed"), new Verb("shine", "shone", "shone"),
            new Verb("shoot", "shot", "shot"), new Verb("show", "showed", "shown"),
            new Verb("shrink", "shrank", "shrunk"), new Verb("shut", "shut", "shut"), new Verb("sing", "sang", "sung"),
            new Verb("sink", "sank", "sunk"), new Verb("sit", "sat", "sat"), new Verb("sleep", "slept", "slept"),
            new Verb("slide", "slid", "slid"), new Verb("speak", "spoke", "spoken"),
            new Verb("spend", "spent", "spent"), new Verb("spin", "spun", "spun"), new Verb("split", "split", "split"),
            new Verb("spread", "spread", "spread"), new Verb("stand", "stood", "stood"),
            new Verb("steal", "stole", "stolen"), new Verb("stick", "stuck", "stuck"),
            new Verb("sting", "stung", "stung"), new Verb("strike", "struck", "struck"),
            new Verb("strive", "strove", "striven"), new Verb("swear", "swore", "sworn"),
            new Verb("sweep", "swept", "swept"), new Verb("swim", "swam", "swum"), new Verb("swing", "swung", "swung"),
            new Verb("take", "took", "taken"), new Verb("teach", "taught", "taught"), new Verb("tear", "tore", "torn"),
            new Verb("tell", "told", "told"), new Verb("think", "thought", "thought"),
            new Verb("throw", "threw", "thrown"), new Verb("undergo", "underwent", "undergone"),
            new Verb("understand", "understood", "understood"), new Verb("undo", "undid", "undone"),
            new Verb("unwind", "unwound", "unwound"), new Verb("uphold", "upheld", "upheld"),
            new Verb("upset", "upset", "upset"), new Verb("wake", "woke", "woken"), new Verb("wear", "wore", "worn"),
            new Verb("weave", "wove", "woven"), new Verb("win", "won", "won"), new Verb("wind", "wound", "wound"),
            new Verb("withdraw", "withdrew", "withdrawn"), new Verb("withhold", "withheld", "withheld"),
            new Verb("write", "wrote", "written"));

    /** Common verbs that are seldom nouns in technical prose, by base; the irregular ones are in {@link #IRREGULAR}. */
    public static final List<String> VERBS = List.of("accept", "achieve", "add", "affect", "agree", "allow", "appear",
            "apply", "argue", "arrive", "ask", "assume", "attempt", "avoid", "become", "begin", "behave", "believe",
            "belong", "bring", "cause", "choose", "claim", "come", "compare", "complain", "concern", "consider",
            "consist", "contain", "continue", "convert", "correspond", "create", "decide", "define", "deliver",
            "depend", "describe", "deserve", "determine", "develop", "differ", "disappear", "discover", "discuss",
            "emerge", "enable", "encourage", "ensure", "enter", "establish", "evaluate", "exist", "expect", "explain",
            "express", "extend", "fail", "feel", "follow", "forget", "gain", "generate", "get", "give", "go", "happen",
            "help", "hide", "ignore", "imagine", "improve", "include", "indicate", "inform", "insist", "intend",
            "introduce", "involve", "keep", "know", "lack", "lead", "learn", "leave", "let", "lie", "live", "look",
            "lose", "maintain", "make", "manage", "mean", "mention", "move", "need", "notice", "obtain", "occur",
            "offer", "omit", "open", "perform", "permit", "persist", "prefer", "prepare", "pretend", "prevent",
            "produce", "propose", "protect", "prove", "provide", "reach", "realize", "receive", "recognize", "reduce",
            "refer", "reflect", "refuse", "regard", "relate", "rely", "remain", "remember", "remind", "remove",
            "repeat", "represent", "require", "resemble", "respond", "result", "reveal", "say", "see", "seek", "seem",
            "select", "send", "serve", "show", "sit", "solve", "speak", "spend", "stand", "stay", "succeed", "suggest",
            "suppose", "survive", "take", "talk", "teach", "tell", "tend", "think", "throw", "treat", "try", "turn",
            "understand", "vary", "want", "wonder", "work");

    /** Nouns that end like adjectives (-al, -ic, -ive, -ant, -ent, -ary, -ory, -ar). */
    public static final List<String> NOUN_LOOKALIKES = List.of("approval", "arrival", "burial", "denial", "disposal",
            "dismissal", "interval", "journal", "material", "portal", "proposal", "referral", "refusal", "removal",
            "renewal", "rental", "retrieval", "reversal", "rival", "signal", "survival", "terminal", "trial",
            "tutorial", "withdrawal", "academic", "chronic", "clinic", "critic", "epic", "graphic", "logic", "magic",
            "mechanic", "metric", "music", "panic", "rubric", "statistic", "topic", "traffic", "alternative", "archive",
            "detective", "directive", "executive", "incentive", "initiative", "motive", "narrative", "objective",
            "perspective", "primitive", "relative", "representative", "accountant", "applicant", "assistant",
            "attendant", "constant", "consultant", "defendant", "descendant", "inhabitant", "merchant", "migrant",
            "participant", "servant", "tenant", "variant", "warrant", "accent", "agent", "client", "component",
            "constituent", "content", "dependent", "event", "extent", "incident", "ingredient", "opponent", "parent",
            "patent", "patient", "percent", "president", "recipient", "resident", "respondent", "segment", "student",
            "talent", "accessory", "anniversary", "binary", "boundary", "category", "commentary", "dictionary",
            "directory", "factory", "glossary", "history", "inventory", "itinerary", "laboratory", "library", "memory",
            "repository", "salary", "secretary", "summary", "territory", "theory", "trajectory", "vocabulary",
            "calendar", "collar", "dollar", "grammar", "pillar", "scholar", "seminar");

    /** Common adjectives (those ending in -able, -ible, -ful, -less or -ous are left to the suffix rules). */
    public static final List<String> ADJECTIVES = List.of("actual", "additional", "available", "bad", "bare", "basic",
            "big", "blank", "brief", "broad", "certain", "cheap", "clean", "clear", "close", "common", "complete",
            "concrete", "correct", "critical", "current", "deep", "different", "difficult", "direct", "due", "early",
            "easy", "empty", "entire", "equal", "exact", "explicit", "extra", "fair", "false", "fast", "few", "final",
            "fine", "first", "flat", "foreign", "formal", "free", "fresh", "full", "general", "global", "good", "great",
            "green", "hard", "heavy", "high", "huge", "implicit", "important", "initial", "inner", "internal", "large",
            "last", "late", "left", "legal", "light", "likely", "little", "local", "long", "loose", "low", "main",
            "major", "manual", "minor", "missing", "modern", "narrow", "natural", "near", "neat", "necessary",
            "negative", "new", "next", "nice", "normal", "null", "old", "optional", "original", "other", "outer", "own",
            "partial", "past", "plain", "poor", "positive", "possible", "potential", "present", "previous", "primary",
            "prior", "private", "proper", "public", "quick", "quiet", "rare", "raw", "ready", "real", "recent", "red",
            "redundant", "regular", "relevant", "remote", "right", "rough", "round", "same", "safe", "scarce", "second",
            "separate", "serious", "several", "short", "simple", "single", "slow", "small", "smart", "soft", "solid",
            "special", "specific", "stable", "stale", "static", "steady", "strange", "strict", "strong", "sufficient",
            "sure", "third", "tight", "tiny", "total", "true", "typical", "unique", "unknown", "upper", "urgent",
            "usual", "valid", "various", "visible", "whole", "wide", "wrong", "young");

    private WordForms() {
    }

    /**
     * The third person singular of a verb's base: {@code es} after s, x, z, ch, sh or o ({@code fixes}, {@code goes}),
     * {@code ies} for a final y after a consonant ({@code applies}), else {@code s} ({@code runs}, {@code says}).
     */
    public static String thirdPerson(String base) {
        if (base.endsWith("s") || base.endsWith("x") || base.endsWith("z") || base.endsWith("ch")
                || base.endsWith("sh") || base.endsWith("o")) {
            return base + "es";
        }
        if (base.endsWith("y") && base.length() > 1
                && "aeiou".indexOf(base.charAt(base.length() - 2)) < 0) {
            return base.substring(0, base.length() - 1) + "ies";
        }
        return base + "s";
    }

    /** The candidates of {@code word}, as the class javadoc says; {@code sentenceStart} as in {@link SuffixRules}. */
    public static List<Candidate> candidates(String word, boolean sentenceStart) {
        String lower = word.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        boolean verbBase = VERBS.contains(lower);
        boolean thirdPerson = false;
        boolean irregularPast = false;
        boolean irregularParticiple = false;

        for (Verb verb : IRREGULAR) {
            if (lower.equals(verb.base())) {
                verbBase = true;
            }
            if (lower.equals(thirdPerson(verb.base()))) {
                thirdPerson = true;
            }
            if (lower.equals(verb.past())) {
                if (verb.past().equals(verb.participle())) {
                    irregularParticiple = true;
                } else {
                    irregularPast = true;
                }
            }
            if (lower.equals(verb.participle())) {
                irregularParticiple = true;
            }
        }
        for (String verb : VERBS) {
            if (lower.equals(thirdPerson(verb))) {
                thirdPerson = true;
                break;
            }
        }

        List<Candidate> result;
        if (irregularPast && !verbBase) {
            result = List.of(new Candidate(Tag.VERB, RULE));
        } else if (irregularParticiple && !verbBase) {
            result = List.of(new Candidate(Tag.VERB, RULE), new Candidate(Tag.ADJ, RULE));
        } else if (verbBase || thirdPerson) {
            result = List.of(new Candidate(Tag.VERB, RULE), new Candidate(Tag.NOUN, RULE));
        } else if (NOUN_LOOKALIKES.contains(lower)) {
            result = List.of(new Candidate(Tag.NOUN, RULE), new Candidate(Tag.ADJ, RULE));
        } else if (ADJECTIVES.contains(lower)) {
            result = List.of(new Candidate(Tag.ADJ, RULE), new Candidate(Tag.NOUN, RULE));
        } else {
            result = List.of();
        }

        if (!result.isEmpty() && !sentenceStart && !word.isEmpty()
                && Character.isUpperCase(word.codePointAt(0))) {
            List<Candidate> capitalized = new ArrayList<>();
            capitalized.add(new Candidate(Tag.PROPN, "capital"));
            capitalized.addAll(result);
            return List.copyOf(capitalized);
        }
        return result;
    }
}
