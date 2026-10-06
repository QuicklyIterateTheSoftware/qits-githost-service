package eu.wohlben.qits.githost.api;

import java.util.List;
import java.util.Optional;

/**
 * The registry of {@link PinFormat}s the version-pin rule applies — the one list an ecosystem is
 * added to. {@code versionPins} on a merge request switches on all of them; there is no per-format
 * flag, because a format only ever decides what is unambiguously a newer pin.
 *
 * <p>A static list rather than CDI beans: the formats are stateless value logic with nothing to
 * inject, and a list keeps the order and the full set readable in one place.
 */
final class PinFormats {

  /** Every format the rule applies. Add one entry per ecosystem; see {@link PinFormat}. */
  static final List<PinFormat> REGISTERED =
      List.of(new MavenPom(), new NpmPackageJson(), new Dockerfile());

  private PinFormats() {}

  /**
   * The one format among {@code formats} that reads {@code path}, or empty when none does — or
   * when more than one does, since picking by registration order would make a decision depend on
   * the order of a list.
   */
  static Optional<PinFormat> forPath(String path, List<PinFormat> formats) {
    List<PinFormat> matching = formats.stream().filter(f -> f.appliesTo(path)).toList();
    return matching.size() == 1 ? Optional.of(matching.get(0)) : Optional.empty();
  }
}
