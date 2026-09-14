package com.pixelreel.client.playback.video;

/** What the media library told us about a stream's colour range, which decides whether it gets tone mapped. */
public enum ToneMapMode {
	/** The source is HDR10/HLG/Dolby Vision, so the PQ curve has to be lifted into a viewable range. */
	HDR,
	/** The source is plain SDR and must be shown exactly as decoded. */
	SDR,
	/** No metadata (live channels). Leave the picture alone unless the frames clearly look like raw PQ. */
	AUTO
}
