package eu.wohlben.qits.githost.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The gitlink-pin rule's version ordering, without a repository. The merge-door tests in {@code
 * RepositoryMergeResourceTest} prove the rule through the wire; this pins the one piece that is
 * easy to get subtly wrong — calendar versions compared as strings put {@code 2026.1001} before
 * {@code 2026.930}.
 */
public class GitlinkPinRuleTest {

  @Test
  public void calendarVersionsAreOrderedSegmentBySegmentAsNumbers() {
    assertThat(GitlinkPinRule.compare("2026.930.9", "2026.930.10"), lessThan(0));
    assertThat(GitlinkPinRule.compare("2026.930.10", "2026.1001.5"), lessThan(0));
    assertThat(GitlinkPinRule.compare("2026.1001.5", "2026.930.10"), greaterThan(0));
    assertThat(GitlinkPinRule.compare("2027.101.1", "2026.1231.235959"), greaterThan(0));
    assertThat(GitlinkPinRule.compare("2026.1018.135253", "2026.1018.135253"), is(0));

    List<String> sorted =
        new ArrayList<>(List.of("2026.1001.5", "2026.930.10", "2026.930.9", "2026.1001.10"));
    sorted.sort(GitlinkPinRule::compare);
    assertThat(sorted, is(List.of("2026.930.9", "2026.930.10", "2026.1001.5", "2026.1001.10")));
  }

  @Test
  public void onlyACalendarVersionIsARelease() {
    for (String tag : List.of("2026.930.9", "2026.1018.135253", "2026.101.0")) {
      assertThat(tag, GitlinkPinRule.CALVER.matcher(tag).matches(), is(true));
    }
    for (String tag :
        List.of("v2026.930.9", "2026.93.1", "2026.10180.1", "2026.930.1234567", "1.2.3", "2026.930")) {
      assertThat(tag, GitlinkPinRule.CALVER.matcher(tag).matches(), is(false));
    }
  }
}
