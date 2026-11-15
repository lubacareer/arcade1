# Asset provenance

The game's space geometry, arena visuals, particles and interface composition are original code in the shared game module. Audio is generated procedurally by the checked-in asset-generation source and bundled for offline use.

The bitmap font uses the open DejaVu typeface. Its license is retained with the font under `assets/fonts`; the generator records the source and output. No proprietary fonts, commercial game artwork or runtime asset services are required.

Asset regeneration is a developer operation. The installed game requires neither Python nor a network connection.
