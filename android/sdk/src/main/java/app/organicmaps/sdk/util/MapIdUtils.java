package app.organicmaps.sdk.util;

import androidx.annotation.Nullable;

public final class MapIdUtils
{
  private MapIdUtils() {}

  /**
   * Strips a trailing ".mwm" extension. Nothing else: Organic Maps country ids
   * legitimately contain spaces (e.g. "Germany_Baden-Wurttemberg_Regierungsbezirk
   * Karlsruhe"), and they must match countries.txt, on-disk file names and the
   * native wear:: virtual-MWM registry exactly. Do NOT replace spaces here —
   * underscores are only for download URLs, handled locally at the URL call site.
   */
  @Nullable
  public static String normalize(@Nullable String mapId)
  {
    if (mapId == null)
      return null;
    if (mapId.endsWith(".mwm"))
      return mapId.substring(0, mapId.length() - 4);
    return mapId;
  }
}
