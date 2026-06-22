# Earth map attribution

`blue_marble_2048.png` is NASA Earth Observatory's **Blue Marble: Land Surface,
Shallow Water, and Shaded Topography** raster, downloaded from NASA's official
image server on 2026-06-21.

- Source asset: https://eoimages.gsfc.nasa.gov/images/imagerecords/57000/57730/land_ocean_ice_2048.png
- Resolution: 2048 × 1024 pixels
- Projection: equirectangular / Plate Carrée
- SHA-256: `B5E0139834C638D10C2C747F4BAC63DF5F9387680C00D87E5A2A9ED9A3DFEA71`
- NASA media usage guidance: https://www.nasa.gov/nasa-brand-center/images-and-media/

The image is bundled so the target tracker remains functional offline.

The `natural_earth` directory contains Natural Earth 1:10m GeoJSON vector data,
downloaded from the Natural Earth vector repository on 2026-06-22:

- `ne_10m_coastline.geojson`
- `ne_10m_admin_0_boundary_lines_land.geojson`
- `ne_10m_rivers_lake_centerlines.geojson`
- Source repository: https://github.com/nvkelso/natural-earth-vector
- Natural Earth terms: https://www.naturalearthdata.com/about/terms-of-use/

Natural Earth data is public domain. These files are bundled and parsed locally;
the application never contacts a map service at runtime.
