/*
 * SonarQube Flutter Plugin - Enables analysis of Dart and Flutter projects into SonarQube.
 * Copyright © 2020 inside|app (contact@insideapp.fr)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.insideapp.sonarqube.dart.lang.issues.dartanalyzer;

import fr.insideapp.sonarqube.dart.lang.issues.RepositoryRule;
import fr.insideapp.sonarqube.dart.lang.issues.RepositoryRuleParser;
import org.junit.Before;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the consistency of the two halves of the dartanalyzer ruleset.
 *
 * <p>The plugin ships two files that must agree:
 * <ul>
 *   <li>{@code analysis_options.yaml} — the lints the analyzer is asked to run;</li>
 *   <li>{@code rules.json} — the rules exposed to SonarQube, whose {@code active}
 *       flag builds the default quality profile.</li>
 * </ul>
 *
 * <p>Nothing forced them to agree before, and they drifted: rules stayed active in
 * the profile after leaving analysis_options.yaml (reporting 0 issues forever,
 * including the losing side of mutually exclusive pairs such as
 * prefer_final_parameters vs avoid_final_parameters), while lints stayed enabled in
 * analysis_options.yaml after being deactivated in rules.json (so the analyzer ran
 * them and the plugin silently discarded the results).
 *
 * <p>The invariant is a bijection:
 * <pre>{@code active(rules.json) == lints(analysis_options.yaml) + diagnostics}</pre>
 * where {@code diagnostics} are analyzer diagnostics rather than lints — they always
 * run and so are legitimately active with no analysis_options.yaml entry.
 *
 * <p>Note this also makes mutually exclusive rules unrepresentable: the analyzer
 * rejects an analysis_options.yaml enabling both sides of a pair
 * ({@code incompatible_lint}), so if the active set equals the enabled set, no pair
 * can be active at once.
 */
public class DartAnalyzerRulesetConsistencyTest {

    private static final String ANALYSIS_OPTIONS_FILE = "/dartanalyzer/analysis_options.yaml";
    private static final String DIAGNOSTICS_FILE = "/dartanalyzer/analyzer-diagnostics.txt";

    private List<RepositoryRule> rules;

    @Before
    public void prepare() throws IOException {
        rules = new RepositoryRuleParser().parse(DartAnalyzerRulesDefinition.RULES_FILE);
    }

    private Set<String> ruleKeys(boolean active) {
        return rules.stream()
                .filter(r -> r.active == active)
                .map(r -> r.key)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @SuppressWarnings("unchecked")
    private Set<String> enabledLints() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(ANALYSIS_OPTIONS_FILE)) {
            assertThat(in).as("%s must be on the classpath", ANALYSIS_OPTIONS_FILE).isNotNull();
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> linter = (Map<String, Object>) root.get("linter");
            assertThat(linter).as("analysis_options.yaml must declare a linter section").isNotNull();
            return new TreeSet<>((List<String>) linter.get("rules"));
        }
    }

    private Set<String> analyzerDiagnostics() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(DIAGNOSTICS_FILE)) {
            assertThat(in).as("%s must be on the classpath", DIAGNOSTICS_FILE).isNotNull();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .collect(Collectors.toCollection(TreeSet::new));
            }
        }
    }

    /**
     * Every lint the analyzer is asked to run must be active in the profile,
     * otherwise the plugin pays to produce issues that SonarQube then discards.
     */
    @Test
    public void everyEnabledLintIsActiveInTheProfile() throws IOException {
        Set<String> discarded = new TreeSet<>(enabledLints());
        discarded.removeAll(ruleKeys(true));

        assertThat(discarded)
                .as("lints enabled in analysis_options.yaml but not active:true in rules.json. "
                        + "The analyzer reports them and the plugin drops the issues on the floor. "
                        + "Either activate them in rules.json or remove them from analysis_options.yaml")
                .isEmpty();
    }

    /**
     * Every active rule must be reachable: either a lint the analyzer is asked to
     * run, or a diagnostic that always runs. Anything else is dead weight in the
     * profile — visible to users, incapable of ever raising an issue.
     */
    @Test
    public void everyActiveRuleIsReachable() throws IOException {
        Set<String> unreachable = new TreeSet<>(ruleKeys(true));
        unreachable.removeAll(enabledLints());
        unreachable.removeAll(analyzerDiagnostics());

        assertThat(unreachable)
                .as("rules active:true in rules.json that the analyzer never runs: not enabled in "
                        + "analysis_options.yaml and not a known diagnostic. They would sit in the "
                        + "default profile reporting 0 issues forever. Either enable the lint in "
                        + "analysis_options.yaml, or set active:false, or (if it is a diagnostic) "
                        + "add it to analyzer-diagnostics.txt")
                .isEmpty();
    }

    /**
     * Deactivated rules must not be left enabled in analysis_options.yaml — the same
     * drift as {@link #everyEnabledLintIsActiveInTheProfile()}, stated from the other
     * side so a failure names the offending file directly.
     */
    @Test
    public void deactivatedRulesAreNotEnabledInAnalysisOptions() throws IOException {
        Set<String> stillEnabled = new TreeSet<>(enabledLints());
        stillEnabled.retainAll(ruleKeys(false));

        assertThat(stillEnabled)
                .as("lints still enabled in analysis_options.yaml while marked active:false in "
                        + "rules.json — remove them from analysis_options.yaml")
                .isEmpty();
    }

    /**
     * Rules.json is the source of both the rule repository and the default profile;
     * an active rule with missing metadata is skipped at runtime with only a warning
     * (see DartProfile), which would silently shrink the profile.
     */
    @Test
    public void everyActiveRuleHasCompleteMetadata() {
        Set<String> incomplete = rules.stream()
                .filter(r -> r.active)
                .filter(r -> r.name == null || r.severity == null || r.type == null || r.description == null)
                .map(r -> r.key)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(incomplete)
                .as("active rules with missing metadata in rules.json are silently skipped when "
                        + "building the default profile")
                .isEmpty();
    }

    /**
     * The diagnostics allowlist is an escape hatch from the bijection; it must not
     * quietly grow to cover rules that are really lints.
     */
    @Test
    public void diagnosticsAllowlistOnlyCoversKnownRules() throws IOException {
        Set<String> unknown = new TreeSet<>(analyzerDiagnostics());
        unknown.removeAll(ruleKeys(true));
        unknown.removeAll(ruleKeys(false));

        assertThat(unknown)
                .as("analyzer-diagnostics.txt lists keys that do not exist in rules.json")
                .isEmpty();

        Set<String> alsoLints = new TreeSet<>(analyzerDiagnostics());
        alsoLints.retainAll(enabledLints());

        assertThat(alsoLints)
                .as("keys listed as analyzer diagnostics but also enabled as lints in "
                        + "analysis_options.yaml — a rule is one or the other")
                .isEmpty();
    }
}
