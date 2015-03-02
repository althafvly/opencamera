package net.sourceforge.opencamera;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;
import android.view.SurfaceHolder;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class CameraController2 extends CameraController {
	private static final String TAG = "CameraController2";

	private CameraDevice camera = null;
	private String cameraIdS = null;
	private CameraCharacteristics characteristics = null;
	private List<Integer> zoom_ratios = null;
	private int current_zoom_value = 0;
	private CameraCaptureSession captureSession = null;
	private CaptureRequest.Builder previewBuilder = null;
	private AutoFocusCallback autofocus_cb = null;
	private FaceDetectionListener face_detection_listener = null;
	private ImageReader imageReader = null;
	private PictureCallback jpeg_cb = null;
	//private ImageReader previewImageReader = null;
	private SurfaceTexture texture = null;
	private Surface surface_texture = null;
	private HandlerThread thread = null; 
	Handler handler = null;

	private int preview_width = 0;
	private int preview_height = 0;
	
	private int picture_width = 0;
	private int picture_height = 0;
	
	private static final int STATE_NORMAL = 0;
	private static final int STATE_WAITING_AUTOFOCUS = 1;
	private static final int STATE_WAITING_PRECAPTURE_START = 2;
	private static final int STATE_WAITING_PRECAPTURE_DONE = 3;
	private int state = STATE_NORMAL;
	
	private MediaActionSound media_action_sound = new MediaActionSound();
	private boolean sounds_enabled = true;
	
	class CameraSettings {
		// keys that we need to store, to pass to the stillBuilder, but doesn't need to be passed to previewBuilder (should set sensible defaults)
		private int rotation = 0;
		private Location location = null;
		private byte jpeg_quality = 90;

		// keys that we have passed to the previewBuilder, that we need to store to also pass to the stillBuilder (should set sensible defaults, or use a has_ boolean if we don't want to set a default)
		private int scene_mode = CameraMetadata.CONTROL_SCENE_MODE_DISABLED;
		private int color_effect = CameraMetadata.CONTROL_EFFECT_MODE_OFF;
		private int white_balance = CameraMetadata.CONTROL_AWB_MODE_AUTO;
		private String flash_value = "flash_off";
		private boolean has_iso = false;
		//private int ae_mode = CameraMetadata.CONTROL_AE_MODE_ON;
		//private int flash_mode = CameraMetadata.FLASH_MODE_OFF;
		private int iso = 0;
		private Rect scalar_crop_region = null; // no need for has_scalar_crop_region, as we can set to null instead
		private boolean has_ae_exposure_compensation = false;
		private int ae_exposure_compensation = 0;
		private boolean has_af_mode = false;
		private int af_mode = CaptureRequest.CONTROL_AF_MODE_AUTO;
		private boolean ae_lock = false;
		private MeteringRectangle [] af_regions = null; // no need for has_scalar_crop_region, as we can set to null instead
		private MeteringRectangle [] ae_regions = null; // no need for has_scalar_crop_region, as we can set to null instead
		private boolean has_face_detect_mode = false;
		private int face_detect_mode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;

		private void setupBuilder(CaptureRequest.Builder builder, boolean is_still) {
			//builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
			//builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
			//builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
			//builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
			//builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);

			setSceneMode(builder);
			setColorEffect(builder);
			setWhiteBalance(builder);
			setAEMode(builder, is_still);
			setCropRegion(builder);
			setExposureCompensation(builder);
			setFocusMode(builder);
			setAutoExposureLock(builder);
			setAFRegions(builder);
			setAERegions(builder);
			setFaceDetectMode(builder);

			if( is_still ) {
				if( location != null ) {
					//builder.set(CaptureRequest.JPEG_GPS_LOCATION, location);
					// settings location messes up date on Nexus 7 and Nexus 6?!
				}
				builder.set(CaptureRequest.JPEG_ORIENTATION, rotation);
				builder.set(CaptureRequest.JPEG_QUALITY, jpeg_quality);
			}
		}

		private boolean setSceneMode(CaptureRequest.Builder builder) {
			if( MyDebug.LOG ) {
				Log.d(TAG, "setSceneMode");
				Log.d(TAG, "builder: " + builder);
			}
			if( builder.get(CaptureRequest.CONTROL_SCENE_MODE) == null && scene_mode == CameraMetadata.CONTROL_SCENE_MODE_DISABLED ) {
				// can leave off
			}
			else if( builder.get(CaptureRequest.CONTROL_SCENE_MODE) == null || builder.get(CaptureRequest.CONTROL_SCENE_MODE) != scene_mode ) {
				if( MyDebug.LOG )
					Log.d(TAG, "setting scene mode: " + scene_mode);
				if( scene_mode == CameraMetadata.CONTROL_SCENE_MODE_DISABLED ) {
					builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
				}
				else {
					builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE);
				}
				builder.set(CaptureRequest.CONTROL_SCENE_MODE, scene_mode);
				return true;
			}
			return false;
		}

		private boolean setColorEffect(CaptureRequest.Builder builder) {
			if( builder.get(CaptureRequest.CONTROL_EFFECT_MODE) == null && color_effect == CameraMetadata.CONTROL_EFFECT_MODE_OFF ) {
				// can leave off
			}
			else if( builder.get(CaptureRequest.CONTROL_EFFECT_MODE) == null || builder.get(CaptureRequest.CONTROL_EFFECT_MODE) != color_effect ) {
				if( MyDebug.LOG )
					Log.d(TAG, "setting color effect: " + color_effect);
				builder.set(CaptureRequest.CONTROL_EFFECT_MODE, color_effect);
				return true;
			}
			return false;
		}

		private boolean setWhiteBalance(CaptureRequest.Builder builder) {
			if( builder.get(CaptureRequest.CONTROL_AWB_MODE) == null && white_balance == CameraMetadata.CONTROL_AWB_MODE_AUTO ) {
				// can leave off
			}
			else if( builder.get(CaptureRequest.CONTROL_AWB_MODE) == null || builder.get(CaptureRequest.CONTROL_AWB_MODE) != white_balance ) {
				if( MyDebug.LOG )
					Log.d(TAG, "setting white balance: " + white_balance);
				builder.set(CaptureRequest.CONTROL_AWB_MODE, white_balance);
				return true;
			}
			return false;
		}

		private boolean setAEMode(CaptureRequest.Builder builder, boolean is_still) {
			if( MyDebug.LOG )
				Log.d(TAG, "setAEMode");
			if( has_iso ) {
				if( MyDebug.LOG ) {
					Log.d(TAG, "manual mode");
					Log.d(TAG, "iso: " + iso);
				}
				builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
				builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
				/*Range<Long> exposure_time_range = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
				if( exposure_time_range != null ) {
				}*/
				// for now set some sensible defaults, as we don't yet expose these controls to the user
				builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 1000000000l/30);
				// set flash via CaptureRequest.FLASH
		    	if( flash_value.equals("flash_off") ) {
					builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
		    	}
		    	else if( flash_value.equals("flash_auto") ) {
					builder.set(CaptureRequest.FLASH_MODE, is_still ? CameraMetadata.FLASH_MODE_SINGLE : CameraMetadata.FLASH_MODE_OFF);
		    	}
		    	else if( flash_value.equals("flash_on") ) {
					builder.set(CaptureRequest.FLASH_MODE, is_still ? CameraMetadata.FLASH_MODE_SINGLE : CameraMetadata.FLASH_MODE_OFF);
		    	}
		    	else if( flash_value.equals("flash_torch") ) {
					builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
		    	}
		    	else if( flash_value.equals("flash_red_eye") ) {
					builder.set(CaptureRequest.FLASH_MODE, is_still ? CameraMetadata.FLASH_MODE_SINGLE : CameraMetadata.FLASH_MODE_OFF);
		    	}
			}
			else {
				if( MyDebug.LOG ) {
					Log.d(TAG, "auto mode");
					Log.d(TAG, "flash_value: " + flash_value);
				}
				// prefer to set flash via the ae mode, except for torch which we can't
		    	if( flash_value.equals("flash_off") ) {
					builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
					builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
		    	}
		    	else if( flash_value.equals("flash_auto") ) {
					builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH);
					builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
		    	}
		    	else if( flash_value.equals("flash_on") ) {
					builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
					builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
		    	}
		    	else if( flash_value.equals("flash_torch") ) {
					builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
					builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
		    	}
		    	else if( flash_value.equals("flash_red_eye") ) {
					builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE);
					builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
		    	}
			}
			return true;
		}

		private void setCropRegion(CaptureRequest.Builder builder) {
			if( scalar_crop_region != null ) {
				builder.set(CaptureRequest.SCALER_CROP_REGION, scalar_crop_region);
			}
		}

		private boolean setExposureCompensation(CaptureRequest.Builder builder) {
			if( !has_ae_exposure_compensation )
				return false;
			if( builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) == null || ae_exposure_compensation != builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) ) {
				if( MyDebug.LOG )
					Log.d(TAG, "change exposure to " + ae_exposure_compensation);
				builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ae_exposure_compensation);
	        	return true;
			}
			return false;
		}

		private void setFocusMode(CaptureRequest.Builder builder) {
			if( has_af_mode )
				builder.set(CaptureRequest.CONTROL_AF_MODE, af_mode);
		}

		private void setAutoExposureLock(CaptureRequest.Builder builder) {
	    	builder.set(CaptureRequest.CONTROL_AE_LOCK, ae_lock);
		}

		private void setAFRegions(CaptureRequest.Builder builder) {
			if( af_regions != null && characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0 ) {
				builder.set(CaptureRequest.CONTROL_AF_REGIONS, af_regions);
			}
		}

		private void setAERegions(CaptureRequest.Builder builder) {
			if( ae_regions != null && characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0 ) {
				builder.set(CaptureRequest.CONTROL_AE_REGIONS, ae_regions);
			}
		}

		private void setFaceDetectMode(CaptureRequest.Builder builder) {
			if( has_face_detect_mode )
				builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, face_detect_mode);
		}
		
		// n.b., if we add more methods, remember to update setupBuilder() above!
	}
	
	private CameraSettings camera_settings = new CameraSettings();
	private boolean push_repeating_request_when_torch_off = false;

	public CameraController2(Context context, int cameraId) {
		if( MyDebug.LOG )
			Log.d(TAG, "create new CameraController2: " + cameraId);

		thread = new HandlerThread("CameraBackground"); 
		thread.start(); 
		handler = new Handler(thread.getLooper());

		class MyStateCallback extends CameraDevice.StateCallback {
			boolean callback_done = false;
			@Override
			public void onOpened(CameraDevice cam) {
				if( MyDebug.LOG )
					Log.d(TAG, "camera opened");
				CameraController2.this.camera = cam;

				// note, this won't start the preview yet, but we create the previewBuilder in order to start setting camera parameters
				createPreviewRequest();

				callback_done = true;
			}

			@Override
			public void onClosed(CameraDevice cam) {
				if( MyDebug.LOG )
					Log.d(TAG, "camera closed");
				// caller should ensure camera variables are set to null
			}

			@Override
			public void onDisconnected(CameraDevice cam) {
				if( MyDebug.LOG )
					Log.d(TAG, "camera disconnected");
				// need to set the camera to null first, as closing the camera may take some time, and we don't want any other operations to continue (if called from main thread)
				CameraController2.this.camera = null;
				if( MyDebug.LOG )
					Log.d(TAG, "onDisconnected: camera is now set to null");
				cam.close();
				if( MyDebug.LOG )
					Log.d(TAG, "onDisconnected: camera is now closed");
				callback_done = true;
			}

			@Override
			public void onError(CameraDevice cam, int error) {
				if( MyDebug.LOG ) {
					Log.d(TAG, "camera error: " + error);
					Log.d(TAG, "received camera: " + cam);
					Log.d(TAG, "actual camera: " + CameraController2.this.camera);
				}
				// need to set the camera to null first, as closing the camera may take some time, and we don't want any other operations to continue (if called from main thread)
				CameraController2.this.camera = null;
				if( MyDebug.LOG )
					Log.d(TAG, "onError: camera is now set to null");
				cam.close();
				if( MyDebug.LOG )
					Log.d(TAG, "onError: camera is now closed");
				callback_done = true;
			}
		};
		MyStateCallback myStateCallback = new MyStateCallback();

		CameraManager manager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
		try {
			this.cameraIdS = manager.getCameraIdList()[cameraId];
			manager.openCamera(cameraIdS, myStateCallback, handler);
		    characteristics = manager.getCameraCharacteristics(cameraIdS);
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to open camera");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			// throw as a RuntimeException instead, as this is what callers will catch
			throw new RuntimeException();
		}

		if( MyDebug.LOG )
			Log.d(TAG, "wait until camera opened...");
		// need to wait until camera is opened
		while( !myStateCallback.callback_done ) {
		}
		if( camera == null ) {
			if( MyDebug.LOG )
				Log.e(TAG, "camera failed to open");
			throw new RuntimeException();
		}
		if( MyDebug.LOG )
			Log.d(TAG, "camera now opened: " + camera);

		/*CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraIdS);
	    StreamConfigurationMap configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
	    android.util.Size [] camera_picture_sizes = configs.getOutputSizes(ImageFormat.JPEG);
		imageReader = ImageReader.newInstance(camera_picture_sizes[0].getWidth(), , ImageFormat.JPEG, 2);*/
	}

	@Override
	void release() {
		if( MyDebug.LOG )
			Log.d(TAG, "release");
		if( thread != null ) {
			thread.quitSafely();
			try {
				thread.join();
				thread = null;
				handler = null;
			}
			catch(InterruptedException e) {
				e.printStackTrace();
			}
		}
		if( captureSession != null ) {
			captureSession.close();
			captureSession = null;
		}
		previewBuilder = null;
		if( camera != null ) {
			camera.close();
			camera = null;
		}
		if( imageReader != null ) {
			imageReader.close();
			imageReader = null;
		}
		/*if( previewImageReader != null ) {
			previewImageReader.close();
			previewImageReader = null;
		}*/
	}

	private List<String> convertFocusModesToValues(int [] supported_focus_modes_arr) {
		if( MyDebug.LOG )
			Log.d(TAG, "convertFocusModesToValues()");
	    List<Integer> supported_focus_modes = new ArrayList<Integer>();
	    for(int i=0;i<supported_focus_modes_arr.length;i++)
	    	supported_focus_modes.add(supported_focus_modes_arr[i]);
	    List<String> output_modes = new Vector<String>();
		if( supported_focus_modes != null ) {
			// also resort as well as converting
			if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ) {
				output_modes.add("focus_mode_auto");
				if( MyDebug.LOG ) {
					Log.d(TAG, " supports focus_mode_auto");
				}
			}
			if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_MACRO) ) {
				output_modes.add("focus_mode_macro");
				if( MyDebug.LOG )
					Log.d(TAG, " supports focus_mode_macro");
			}
			if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ) {
				output_modes.add("focus_mode_manual");
				if( MyDebug.LOG ) {
					Log.d(TAG, " supports focus_mode_manual");
				}
			}
			if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_EDOF) ) {
				output_modes.add("focus_mode_edof");
				if( MyDebug.LOG )
					Log.d(TAG, " supports focus_mode_edof");
			}
			if( supported_focus_modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ) {
				output_modes.add("focus_mode_continuous_video");
				if( MyDebug.LOG )
					Log.d(TAG, " supports focus_mode_continuous_video");
			}
		}
		return output_modes;
	}

	String getAPI() {
		return "Camera2 (Android L)";
	}
	
	@Override
	CameraFeatures getCameraFeatures() {
		if( MyDebug.LOG )
			Log.d(TAG, "getCameraFeatures()");
		CameraFeatures camera_features = new CameraFeatures();
		if( MyDebug.LOG ) {
			int hardware_level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
			if( hardware_level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY )
				Log.d(TAG, "Hardware Level: LEGACY");
			else if( hardware_level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED )
				Log.d(TAG, "Hardware Level: LIMITED");
			else if( hardware_level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL )
				Log.d(TAG, "Hardware Level: FULL");
			else
				Log.e(TAG, "Unknown Hardware Level!");
		}

		float max_zoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
		camera_features.is_zoom_supported = max_zoom > 0.0f;
		if( MyDebug.LOG )
			Log.d(TAG, "max_zoom: " + max_zoom);
		if( camera_features.is_zoom_supported ) {
			// set 20 steps per 2x factor
			final int steps_per_2x_factor = 20;
			//final double scale_factor = Math.pow(2.0, 1.0/(double)steps_per_2x_factor);
			int n_steps =(int)( (steps_per_2x_factor * Math.log(max_zoom + 1.0e-11)) / Math.log(2.0));
			final double scale_factor = Math.pow(max_zoom, 1.0/(double)n_steps);
			if( MyDebug.LOG ) {
				Log.d(TAG, "n_steps: " + n_steps);
				Log.d(TAG, "scale_factor: " + scale_factor);
			}
			camera_features.zoom_ratios = new ArrayList<Integer>();
			camera_features.zoom_ratios.add(100);
			double zoom = 1.0;
			for(int i=0;i<n_steps-1;i++) {
				zoom *= scale_factor;
				camera_features.zoom_ratios.add((int)(zoom*100));
			}
			camera_features.zoom_ratios.add((int)(max_zoom*100));
			camera_features.max_zoom = camera_features.zoom_ratios.size()-1;
			this.zoom_ratios = camera_features.zoom_ratios;
		}
		else {
			this.zoom_ratios = null;
		}

		int [] face_modes = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
		camera_features.supports_face_detection = false;
		for(int i=0;i<face_modes.length && !camera_features.supports_face_detection;i++) {
			if( face_modes[i] == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE ) {
				camera_features.supports_face_detection = true;
			}
		}
		if( camera_features.supports_face_detection ) {
			int face_count = characteristics.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT);
			if( face_count <= 0 ) {
				camera_features.supports_face_detection = false;
			}
		}

		StreamConfigurationMap configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

	    android.util.Size [] camera_picture_sizes = configs.getOutputSizes(ImageFormat.JPEG);
		camera_features.picture_sizes = new ArrayList<CameraController.Size>();
		for(android.util.Size camera_size : camera_picture_sizes) {
			if( MyDebug.LOG )
				Log.d(TAG, "picture size: " + camera_size.getWidth() + " x " + camera_size.getHeight());
			camera_features.picture_sizes.add(new CameraController.Size(camera_size.getWidth(), camera_size.getHeight()));
		}

	    android.util.Size [] camera_video_sizes = configs.getOutputSizes(MediaRecorder.class);
		camera_features.video_sizes = new ArrayList<CameraController.Size>();
		for(android.util.Size camera_size : camera_video_sizes) {
			if( MyDebug.LOG )
				Log.d(TAG, "video size: " + camera_size.getWidth() + " x " + camera_size.getHeight());
			if( camera_size.getWidth() > 3840 || camera_size.getHeight() > 2160 )
				continue; // Nexus 6 returns these, even though not supported?!
			camera_features.video_sizes.add(new CameraController.Size(camera_size.getWidth(), camera_size.getHeight()));
		}

		android.util.Size [] camera_preview_sizes = configs.getOutputSizes(SurfaceTexture.class);
		camera_features.preview_sizes = new ArrayList<CameraController.Size>();
		for(android.util.Size camera_size : camera_preview_sizes) {
			if( MyDebug.LOG )
				Log.d(TAG, "preview size: " + camera_size.getWidth() + " x " + camera_size.getHeight());
			if( camera_size.getWidth() > 1920 || camera_size.getHeight() > 1440 )
				continue; // Nexus 6 returns these, even though not supported?! (get green corruption lines if we allow these)
			camera_features.preview_sizes.add(new CameraController.Size(camera_size.getWidth(), camera_size.getHeight()));
		}
		
		if( characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ) {
			camera_features.supported_flash_values = new ArrayList<String>();
			camera_features.supported_flash_values.add("flash_off");
			camera_features.supported_flash_values.add("flash_auto");
			camera_features.supported_flash_values.add("flash_on");
			camera_features.supported_flash_values.add("flash_torch");
			camera_features.supported_flash_values.add("flash_red_eye");
		}

		int [] supported_focus_modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES); // Android format
		camera_features.supported_focus_values = convertFocusModesToValues(supported_focus_modes); // convert to our format (also resorts)
		camera_features.max_num_focus_areas = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);

		camera_features.is_exposure_lock_supported = true;
		
		// TODO: is_video_stabilization_supported

		Range<Integer> exposure_range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
		camera_features.min_exposure = exposure_range.getLower();
		camera_features.max_exposure = exposure_range.getUpper();
		camera_features.exposure_step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).floatValue();

		camera_features.can_disable_shutter_sound = true;

		return camera_features;
	}

	private String convertSceneMode(int value2) {
		String value = null;
		switch( value2 ) {
		case CameraMetadata.CONTROL_SCENE_MODE_ACTION:
			value = "action";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_BARCODE:
			value = "barcode";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_BEACH:
			value = "beach";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT:
			value = "candlelight";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_DISABLED:
			value = "auto";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS:
			value = "fireworks";
			break;
		// "hdr" no longer available in Camera2
		/*case CameraMetadata.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO:
			// new for Camera2
			value = "high-speed-video";
			break;*/
		case CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE:
			value = "landscape";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_NIGHT:
			value = "night";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT:
			value = "night-portrait";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_PARTY:
			value = "party";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT:
			value = "portrait";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_SNOW:
			value = "snow";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_SPORTS:
			value = "sports";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO:
			value = "steadyphoto";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_SUNSET:
			value = "sunset";
			break;
		case CameraMetadata.CONTROL_SCENE_MODE_THEATRE:
			value = "theatre";
			break;
		default:
			if( MyDebug.LOG )
				Log.d(TAG, "unknown scene mode: " + value2);
			value = null;
			break;
		}
		return value;
	}

	@Override
	SupportedValues setSceneMode(String value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setSceneMode: " + value);
		// we convert to/from strings to be compatible with original Android Camera API
		String default_value = getDefaultSceneMode();
		int [] values2 = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
		boolean has_disabled = false;
		List<String> values = new ArrayList<String>();
		for(int i=0;i<values2.length;i++) {
			if( values2[i] == CameraMetadata.CONTROL_SCENE_MODE_DISABLED )
				has_disabled = true;
			String this_value = convertSceneMode(values2[i]);
			if( this_value != null ) {
				values.add(this_value);
			}
		}
		if( !has_disabled ) {
			values.add(0, "auto");
		}
		SupportedValues supported_values = checkModeIsSupported(values, value, default_value);
		if( supported_values != null ) {
			int selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_DISABLED;
			if( supported_values.selected_value.equals("action") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_ACTION;
			}
			else if( supported_values.selected_value.equals("barcode") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_BARCODE;
			}
			else if( supported_values.selected_value.equals("beach") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_BEACH;
			}
			else if( supported_values.selected_value.equals("candlelight") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT;
			}
			else if( supported_values.selected_value.equals("auto") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_DISABLED;
			}
			else if( supported_values.selected_value.equals("fireworks") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS;
			}
			// "hdr" no longer available in Camera2
			else if( supported_values.selected_value.equals("landscape") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE;
			}
			else if( supported_values.selected_value.equals("night") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_NIGHT;
			}
			else if( supported_values.selected_value.equals("night-portrait") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT;
			}
			else if( supported_values.selected_value.equals("party") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_PARTY;
			}
			else if( supported_values.selected_value.equals("portrait") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT;
			}
			else if( supported_values.selected_value.equals("snow") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_SNOW;
			}
			else if( supported_values.selected_value.equals("sports") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_SPORTS;
			}
			else if( supported_values.selected_value.equals("steadyphoto") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO;
			}
			else if( supported_values.selected_value.equals("sunset") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_SUNSET;
			}
			else if( supported_values.selected_value.equals("theatre") ) {
				selected_value2 = CameraMetadata.CONTROL_SCENE_MODE_THEATRE;
			}
			else {
				if( MyDebug.LOG )
					Log.d(TAG, "unknown selected_value: " + supported_values.selected_value);
			}

			camera_settings.scene_mode = selected_value2;
			if( camera_settings.setSceneMode(previewBuilder) ) {
		    	setRepeatingRequest();
			}
		}
		return supported_values;
	}
	
	@Override
	public String getSceneMode() {
		if( previewBuilder.get(CaptureRequest.CONTROL_SCENE_MODE) == null )
			return null;
		int value2 = previewBuilder.get(CaptureRequest.CONTROL_SCENE_MODE);
		String value = convertSceneMode(value2);
		return value;
	}

	private String convertColorEffect(int value2) {
		String value = null;
		switch( value2 ) {
		case CameraMetadata.CONTROL_EFFECT_MODE_AQUA:
			value = "aqua";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD:
			value = "blackboard";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_MONO:
			value = "mono";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE:
			value = "negative";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_OFF:
			value = "none";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE:
			value = "posterize";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_SEPIA:
			value = "sepia";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE:
			value = "solarize";
			break;
		case CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD:
			value = "whiteboard";
			break;
		default:
			if( MyDebug.LOG )
				Log.d(TAG, "unknown effect mode: " + value2);
			value = null;
			break;
		}
		return value;
	}

	@Override
	SupportedValues setColorEffect(String value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setColorEffect: " + value);
		// we convert to/from strings to be compatible with original Android Camera API
		String default_value = getDefaultColorEffect();
		int [] values2 = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS);
		List<String> values = new ArrayList<String>();
		for(int i=0;i<values2.length;i++) {
			String this_value = convertColorEffect(values2[i]);
			if( this_value != null ) {
				values.add(this_value);
			}
		}
		SupportedValues supported_values = checkModeIsSupported(values, value, default_value);
		if( supported_values != null ) {
			int selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_OFF;
			if( supported_values.selected_value.equals("aqua") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_AQUA;
			}
			else if( supported_values.selected_value.equals("blackboard") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD;
			}
			else if( supported_values.selected_value.equals("mono") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_MONO;
			}
			else if( supported_values.selected_value.equals("negative") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE;
			}
			else if( supported_values.selected_value.equals("none") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_OFF;
			}
			else if( supported_values.selected_value.equals("posterize") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE;
			}
			else if( supported_values.selected_value.equals("sepia") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_SEPIA;
			}
			else if( supported_values.selected_value.equals("solarize") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE;
			}
			else if( supported_values.selected_value.equals("whiteboard") ) {
				selected_value2 = CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD;
			}
			else {
				if( MyDebug.LOG )
					Log.d(TAG, "unknown selected_value: " + supported_values.selected_value);
			}

			camera_settings.color_effect = selected_value2;
			if( camera_settings.setColorEffect(previewBuilder) ) {
		    	setRepeatingRequest();
			}
		}
		return supported_values;
	}

	@Override
	public String getColorEffect() {
		if( previewBuilder.get(CaptureRequest.CONTROL_EFFECT_MODE) == null )
			return null;
		int value2 = previewBuilder.get(CaptureRequest.CONTROL_EFFECT_MODE);
		String value = convertColorEffect(value2);
		return value;
	}

	private String convertWhiteBalance(int value2) {
		String value = null;
		switch( value2 ) {
		case CameraMetadata.CONTROL_AWB_MODE_AUTO:
			value = "auto";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT:
			value = "cloudy-daylight";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT:
			value = "daylight";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT:
			value = "fluorescent";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT:
			value = "incandescent";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_SHADE:
			value = "shade";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_TWILIGHT:
			value = "twilight";
			break;
		case CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT:
			value = "warm-fluorescent";
			break;
		default:
			if( MyDebug.LOG )
				Log.d(TAG, "unknown white balance: " + value2);
			value = null;
			break;
		}
		return value;
	}

	@Override
	SupportedValues setWhiteBalance(String value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setWhiteBalance: " + value);
		// we convert to/from strings to be compatible with original Android Camera API
		String default_value = getDefaultWhiteBalance();
		int [] values2 = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
		List<String> values = new ArrayList<String>();
		for(int i=0;i<values2.length;i++) {
			String this_value = convertWhiteBalance(values2[i]);
			if( this_value != null ) {
				values.add(this_value);
			}
		}
		SupportedValues supported_values = checkModeIsSupported(values, value, default_value);
		if( supported_values != null ) {
			int selected_value2 = CameraMetadata.CONTROL_AWB_MODE_AUTO;
			if( supported_values.selected_value.equals("auto") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_AUTO;
			}
			else if( supported_values.selected_value.equals("cloudy-daylight") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
			}
			else if( supported_values.selected_value.equals("daylight") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT;
			}
			else if( supported_values.selected_value.equals("fluorescent") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT;
			}
			else if( supported_values.selected_value.equals("incandescent") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT;
			}
			else if( supported_values.selected_value.equals("shade") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_SHADE;
			}
			else if( supported_values.selected_value.equals("twilight") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_TWILIGHT;
			}
			else if( supported_values.selected_value.equals("warm-fluorescent") ) {
				selected_value2 = CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT;
			}
			else {
				if( MyDebug.LOG )
					Log.d(TAG, "unknown selected_value: " + supported_values.selected_value);
			}

			camera_settings.white_balance = selected_value2;
			if( camera_settings.setWhiteBalance(previewBuilder) ) {
		    	setRepeatingRequest();
			}
		}
		return supported_values;
	}

	@Override
	public String getWhiteBalance() {
		if( previewBuilder.get(CaptureRequest.CONTROL_AWB_MODE) == null )
			return null;
		int value2 = previewBuilder.get(CaptureRequest.CONTROL_AWB_MODE);
		String value = convertWhiteBalance(value2);
		return value;
	}

	@Override
	SupportedValues setISO(String value) {
		String default_value = getDefaultISO();
		Range<Integer> iso_range = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
		if( iso_range == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "iso not supported");
			return null;
		}
		if( MyDebug.LOG )
			Log.d(TAG, "iso range from " + iso_range.getLower() + " to " + iso_range.getUpper());
		List<String> values = new ArrayList<String>();
		values.add("auto");
		int [] iso_values = {100, 200, 400, 800, 1600};
		for(int i=0;i<iso_values.length;i++) {
			if( iso_values[i] >= iso_range.getLower() && iso_values[i] <= iso_range.getUpper() ) {
				values.add("" + iso_values[i]);
			}
		}
		SupportedValues supported_values = checkModeIsSupported(values, value, default_value);
		if( supported_values != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "set iso to: " + supported_values.selected_value);
			if( supported_values.selected_value.equals("auto") ) {
				camera_settings.has_iso = false;
				camera_settings.iso = 0;
				if( camera_settings.setAEMode(previewBuilder, false) ) {
			    	setRepeatingRequest();
				}
			}
			else {
				try {
					int selected_value2 = Integer.parseInt(supported_values.selected_value);
					if( MyDebug.LOG )
						Log.d(TAG, "iso: " + selected_value2);
					camera_settings.has_iso = true;
					camera_settings.iso = selected_value2;
					if( camera_settings.setAEMode(previewBuilder, false) ) {
				    	setRepeatingRequest();
					}
				}
				catch(NumberFormatException exception) {
					if( MyDebug.LOG )
						Log.d(TAG, "iso invalid format, can't parse to int");
					camera_settings.has_iso = false;
					camera_settings.iso = 0;
					if( camera_settings.setAEMode(previewBuilder, false) ) {
				    	setRepeatingRequest();
					}
				}
			}
		}
		return supported_values;
	}

	@Override
	String getISOKey() {
		return "";
	}

	@Override
	public Size getPictureSize() {
		Size size = new Size(picture_width, picture_height);
		return size;
	}

	@Override
	void setPictureSize(int width, int height) {
		if( MyDebug.LOG )
			Log.d(TAG, "setPictureSize: " + width + " x " + height);
		if( camera == null ) {
			if( MyDebug.LOG )
				Log.e(TAG, "no camera");
			return;
		}
		if( captureSession != null ) {
			// can only call this when captureSession not created - as the surface of the imageReader we create has to match the surface we pass to the captureSession
			if( MyDebug.LOG )
				Log.e(TAG, "can't set picture size when captureSession running!");
			throw new RuntimeException();
		}
		this.picture_width = width;
		this.picture_height = height;
	}

	private void createPictureImageReader() {
		if( MyDebug.LOG )
			Log.d(TAG, "createPictureImageReader");
		if( captureSession != null ) {
			// can only call this when captureSession not created - as the surface of the imageReader we create has to match the surface we pass to the captureSession
			if( MyDebug.LOG )
				Log.e(TAG, "can't create picture image reader when captureSession running!");
			throw new RuntimeException();
		}
		if( imageReader != null ) {
			imageReader.close();
		}
		if( picture_width == 0 || picture_height == 0 ) {
			if( MyDebug.LOG )
				Log.e(TAG, "application needs to call setPictureSize()");
			throw new RuntimeException();
		}
		imageReader = ImageReader.newInstance(picture_width, picture_height, ImageFormat.JPEG, 2); 
		if( MyDebug.LOG ) {
			Log.d(TAG, "created new imageReader: " + imageReader.toString());
			Log.d(TAG, "imageReader surface: " + imageReader.getSurface().toString());
		}
		imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
			@Override
			public void onImageAvailable(ImageReader reader) {
				if( MyDebug.LOG )
					Log.d(TAG, "new still image available");
				if( jpeg_cb == null ) {
					if( MyDebug.LOG )
						Log.d(TAG, "no picture callback available");
					return;
				}
				Image image = reader.acquireNextImage();
	            ByteBuffer buffer = image.getPlanes()[0].getBuffer(); 
	            byte [] bytes = new byte[buffer.remaining()]; 
				if( MyDebug.LOG )
					Log.d(TAG, "read " + bytes.length + " bytes");
	            buffer.get(bytes);
	            image.close();
	            image = null;
	            jpeg_cb.onPictureTaken(bytes);
	            jpeg_cb = null;
				if( MyDebug.LOG )
					Log.d(TAG, "done onImageAvailable");
			}
		}, null);
	}
	@Override
	public Size getPreviewSize() {
		return new Size(preview_width, preview_height);
	}

	@Override
	void setPreviewSize(int width, int height) {
		if( MyDebug.LOG )
			Log.d(TAG, "setPreviewSize: " + width + " , " + height);
		/*if( texture != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "set size of preview texture");
			texture.setDefaultBufferSize(width, height);
		}*/
		preview_width = width;
		preview_height = height;
		/*if( previewImageReader != null ) {
			previewImageReader.close();
		}
		previewImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2); 
		*/
	}

	@Override
	void setVideoStabilization(boolean enabled) {
		// TODO Auto-generated method stub

	}

	@Override
	public boolean getVideoStabilization() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public int getJpegQuality() {
		return this.camera_settings.jpeg_quality;
	}

	@Override
	void setJpegQuality(int quality) {
		if( quality < 0 || quality > 100 ) {
			if( MyDebug.LOG )
				Log.e(TAG, "invalid jpeg quality" + quality);
			throw new RuntimeException();
		}
		this.camera_settings.jpeg_quality = (byte)quality;
	}

	@Override
	public int getZoom() {
		return this.current_zoom_value;
	}

	@Override
	void setZoom(int value) {
		if( zoom_ratios == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "zoom not supported");
			return;
		}
		if( value < 0 || value > zoom_ratios.size() ) {
			if( MyDebug.LOG )
				Log.e(TAG, "invalid zoom value" + value);
			throw new RuntimeException();
		}
		float zoom = zoom_ratios.get(value)/100.0f;
		Rect sensor_rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		int left = sensor_rect.centerX();
		int right = left;
		int top = sensor_rect.centerY();
		int bottom = top;
		int hwidth = (int)(sensor_rect.width() / (2.0*zoom));
		int hheight = (int)(sensor_rect.height() / (2.0*zoom));
		left -= hwidth;
		right += hwidth;
		top -= hheight;
		bottom += hheight;
		if( MyDebug.LOG ) {
			Log.d(TAG, "zoom: " + zoom);
			Log.d(TAG, "hwidth: " + hwidth);
			Log.d(TAG, "hheight: " + hheight);
		}
		camera_settings.scalar_crop_region = new Rect(left, top, right, bottom);
		camera_settings.setCropRegion(previewBuilder);
    	setRepeatingRequest();
    	this.current_zoom_value = value;
	}
	
	@Override
	int getExposureCompensation() {
		if( previewBuilder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) == null )
			return 0;
		return previewBuilder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
	}

	@Override
	// Returns whether exposure was modified
	boolean setExposureCompensation(int new_exposure) {
		camera_settings.has_ae_exposure_compensation = true;
		camera_settings.ae_exposure_compensation = new_exposure;
		if( camera_settings.setExposureCompensation(previewBuilder) ) {
	    	setRepeatingRequest();
        	return true;
		}
		return false;
	}
	
	@Override
	void setPreviewFpsRange(int min, int max) {
		// TODO Auto-generated method stub

	}

	@Override
	List<int[]> getSupportedPreviewFpsRange() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getDefaultSceneMode() {
		return "auto";
	}

	@Override
	public String getDefaultColorEffect() {
		return "none";
	}

	@Override
	public String getDefaultWhiteBalance() {
		return "auto";
	}

	@Override
	public String getDefaultISO() {
		return "auto";
	}

	@Override
	void setFocusValue(String focus_value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setFocusValue: " + focus_value);
		int focus_mode = CaptureRequest.CONTROL_AF_MODE_AUTO;
    	if( focus_value.equals("focus_mode_auto") || focus_value.equals("focus_mode_manual") ) {
    		focus_mode = CaptureRequest.CONTROL_AF_MODE_AUTO;
    	}
    	else if( focus_value.equals("focus_mode_macro") ) {
    		focus_mode = CaptureRequest.CONTROL_AF_MODE_MACRO;
    	}
    	else if( focus_value.equals("focus_mode_edof") ) {
    		focus_mode = CaptureRequest.CONTROL_AF_MODE_EDOF;
    	}
    	else if( focus_value.equals("focus_mode_continuous_video") ) {
    		focus_mode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
    	}
    	else {
    		if( MyDebug.LOG )
    			Log.d(TAG, "setFocusValue() received unknown focus value " + focus_value);
    		return;
    	}
    	camera_settings.has_af_mode = true;
    	camera_settings.af_mode = focus_mode;
    	camera_settings.setFocusMode(previewBuilder);
    	setRepeatingRequest();
	}
	
	private String convertFocusModeToValue(int focus_mode) {
		if( MyDebug.LOG )
			Log.d(TAG, "convertFocusModeToValue: " + focus_mode);
		String focus_value = "";
		if( focus_mode == CaptureRequest.CONTROL_AF_MODE_AUTO ) {
    		focus_value = "focus_mode_auto";
    	}
		else if( focus_mode == CaptureRequest.CONTROL_AF_MODE_MACRO ) {
    		focus_value = "focus_mode_macro";
    	}
		else if( focus_mode == CaptureRequest.CONTROL_AF_MODE_EDOF ) {
    		focus_value = "focus_mode_edof";
    	}
		else if( focus_mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO ) {
    		focus_value = "focus_mode_continuous_video";
    	}
    	return focus_value;
	}
	
	@Override
	public String getFocusValue() {
		int focus_mode = previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) != null ?
				previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) : CaptureRequest.CONTROL_AF_MODE_AUTO;
		return convertFocusModeToValue(focus_mode);
	}

	@Override
	void setFlashValue(String flash_value) {
		if( MyDebug.LOG )
			Log.d(TAG, "setFlashValue: " + flash_value);
		if( !characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ) {
			return;
		}
		else if( camera_settings.flash_value.equals(flash_value) ) {
			return;
		}

		if( camera_settings.flash_value.equals("flash_torch") ) {
			// hack - first need to turn torch off, otherwise torch remains on (at least on Nexus 6)
			camera_settings.flash_value = "flash_off";
			if( camera_settings.setAEMode(previewBuilder, false) ) {
		    	setRepeatingRequest();
			}
			camera_settings.flash_value = flash_value;
			if( camera_settings.setAEMode(previewBuilder, false) ) {
				// need to wait until torch actually turned off
				push_repeating_request_when_torch_off = true;
			}
		}
		else {
			camera_settings.flash_value = flash_value;
			if( camera_settings.setAEMode(previewBuilder, false) ) {
		    	setRepeatingRequest();
			}
		}
	}

	@Override
	public String getFlashValue() {
		// returns "" if flash isn't supported
		if( !characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ) {
			return "";
		}
		return camera_settings.flash_value;
	}

	@Override
	void setRecordingHint(boolean hint) {
		// not relevant for CameraController2
	}

	@Override
	void setAutoExposureLock(boolean enabled) {
		camera_settings.ae_lock = enabled;
		camera_settings.setAutoExposureLock(previewBuilder);
    	setRepeatingRequest();
	}
	
	@Override
	public boolean getAutoExposureLock() {
		if( previewBuilder.get(CaptureRequest.CONTROL_AE_LOCK) == null )
			return false;
    	return previewBuilder.get(CaptureRequest.CONTROL_AE_LOCK);
	}

	@Override
	void setRotation(int rotation) {
		this.camera_settings.rotation = rotation;
	}

	@Override
	void setLocationInfo(Location location) {
		if( MyDebug.LOG )
			Log.d(TAG, "setLocationInfo: " + location.getLongitude() + " , " + location.getLatitude());
		this.camera_settings.location = location;
	}

	@Override
	void removeLocationInfo() {
		this.camera_settings.location = null;
	}

	@Override
	void enableShutterSound(boolean enabled) {
		this.sounds_enabled = enabled;
	}

	private Rect convertRectToCamera2(Rect sensor_rect, Rect rect) {
		// CameraController.Area is always [-1000, -1000] to [1000, 1000]
		// but for CameraController2, we must convert to [0, 0] to [sensor width-1, sensor height-1] for use as a MeteringRectangle
		double left_f = (rect.left+1000)/2000.0;
		double top_f = (rect.top+1000)/2000.0;
		double right_f = (rect.right+1000)/2000.0;
		double bottom_f = (rect.bottom+1000)/2000.0;
		int left = (int)(left_f * (sensor_rect.width()-1));
		int right = (int)(right_f * (sensor_rect.width()-1));
		int top = (int)(top_f * (sensor_rect.height()-1));
		int bottom = (int)(bottom_f * (sensor_rect.height()-1));
		left = Math.max(left, 0);
		right = Math.max(right, 0);
		top = Math.max(top, 0);
		bottom = Math.max(bottom, 0);
		left = Math.min(left, sensor_rect.width()-1);
		right = Math.min(right, sensor_rect.width()-1);
		top = Math.min(top, sensor_rect.height()-1);
		bottom = Math.min(bottom, sensor_rect.height()-1);

		Rect camera2_rect = new Rect(left, top, right, bottom);
		return camera2_rect;
	}

	private MeteringRectangle convertAreaToMeteringRectangle(Rect sensor_rect, Area area) {
		Rect camera2_rect = convertRectToCamera2(sensor_rect, area.rect);
		MeteringRectangle metering_rectangle = new MeteringRectangle(camera2_rect, area.weight);
		return metering_rectangle;
	}

	private Rect convertRectFromCamera2(Rect sensor_rect, Rect camera2_rect) {
		// inverse of convertRectToCamera2()
		double left_f = camera2_rect.left/(double)(sensor_rect.width()-1);
		double top_f = camera2_rect.top/(double)(sensor_rect.height()-1);
		double right_f = camera2_rect.right/(double)(sensor_rect.width()-1);
		double bottom_f = camera2_rect.bottom/(double)(sensor_rect.height()-1);
		int left = (int)(left_f * 2000) - 1000;
		int right = (int)(right_f * 2000) - 1000;
		int top = (int)(top_f * 2000) - 1000;
		int bottom = (int)(bottom_f * 2000) - 1000;

		left = Math.max(left, -1000);
		right = Math.max(right, -1000);
		top = Math.max(top, -1000);
		bottom = Math.max(bottom, -1000);
		left = Math.min(left, 1000);
		right = Math.min(right, 1000);
		top = Math.min(top, 1000);
		bottom = Math.min(bottom, 1000);

		Rect rect = new Rect(left, top, right, bottom);
		return rect;
	}

	private Area convertMeteringRectangleToArea(Rect sensor_rect, MeteringRectangle metering_rectangle) {
		Rect area_rect = convertRectFromCamera2(sensor_rect, metering_rectangle.getRect());
		Area area = new Area(area_rect, metering_rectangle.getMeteringWeight());
		return area;
	}
	
	private CameraController.Face convertFromCameraFace(Rect sensor_rect, android.hardware.camera2.params.Face camera2_face) {
		Rect area_rect = convertRectFromCamera2(sensor_rect, camera2_face.getBounds());
		CameraController.Face face = new CameraController.Face(camera2_face.getScore(), area_rect);
		return face;
	}

	@Override
	boolean setFocusAndMeteringArea(List<Area> areas) {
		Rect sensor_rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		if( MyDebug.LOG )
			Log.d(TAG, "sensor_rect: " + sensor_rect.left + " , " + sensor_rect.top + " x " + sensor_rect.right + " , " + sensor_rect.bottom);
		boolean has_focus = false;
		boolean has_metering = false;
		if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0 ) {
			has_focus = true;
			camera_settings.af_regions = new MeteringRectangle[areas.size()];
			int i = 0;
			for(CameraController.Area area : areas) {
				camera_settings.af_regions[i++] = convertAreaToMeteringRectangle(sensor_rect, area);
			}
			camera_settings.setAFRegions(previewBuilder);
		}
		else
			camera_settings.af_regions = null;
		if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0 ) {
			has_metering = true;
			camera_settings.ae_regions = new MeteringRectangle[areas.size()];
			int i = 0;
			for(CameraController.Area area : areas) {
				camera_settings.ae_regions[i++] = convertAreaToMeteringRectangle(sensor_rect, area);
			}
			camera_settings.setAERegions(previewBuilder);
		}
		else
			camera_settings.ae_regions = null;
		if( has_focus || has_metering ) {
			setRepeatingRequest();
		}
		return has_focus;
	}
	
	@Override
	void clearFocusAndMetering() {
		Rect sensor_rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		boolean has_focus = false;
		boolean has_metering = false;
		if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0 ) {
			has_focus = true;
			camera_settings.af_regions = new MeteringRectangle[1];
			camera_settings.af_regions[0] = new MeteringRectangle(0, 0, sensor_rect.width()-1, sensor_rect.height()-1, 0);
			camera_settings.setAFRegions(previewBuilder);
		}
		else
			camera_settings.af_regions = null;
		if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0 ) {
			has_metering = true;
			camera_settings.ae_regions = new MeteringRectangle[1];
			camera_settings.ae_regions[0] = new MeteringRectangle(0, 0, sensor_rect.width()-1, sensor_rect.height()-1, 0);
			camera_settings.setAERegions(previewBuilder);
		}
		else
			camera_settings.ae_regions = null;
		if( has_focus || has_metering ) {
			setRepeatingRequest();
		}
	}

	@Override
	public List<Area> getFocusAreas() {
		if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) == 0 )
			return null;
    	MeteringRectangle [] metering_rectangles = previewBuilder.get(CaptureRequest.CONTROL_AF_REGIONS);
    	if( metering_rectangles == null )
    		return null;
		Rect sensor_rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		camera_settings.af_regions[0] = new MeteringRectangle(0, 0, sensor_rect.width()-1, sensor_rect.height()-1, 0);
		if( metering_rectangles.length == 1 && metering_rectangles[0].getRect().left == 0 && metering_rectangles[0].getRect().top == 0 && metering_rectangles[0].getRect().right == sensor_rect.width()-1 && metering_rectangles[0].getRect().bottom == sensor_rect.height()-1 ) {
			// for compatibility with CameraController1
			return null;
		}
		List<Area> areas = new ArrayList<CameraController.Area>();
		for(int i=0;i<metering_rectangles.length;i++) {
			areas.add(convertMeteringRectangleToArea(sensor_rect, metering_rectangles[i]));
		}
		return areas;
	}

	@Override
	public List<Area> getMeteringAreas() {
		if( characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) == 0 )
			return null;
    	MeteringRectangle [] metering_rectangles = previewBuilder.get(CaptureRequest.CONTROL_AE_REGIONS);
    	if( metering_rectangles == null )
    		return null;
		Rect sensor_rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		if( metering_rectangles.length == 1 && metering_rectangles[0].getRect().left == 0 && metering_rectangles[0].getRect().top == 0 && metering_rectangles[0].getRect().right == sensor_rect.width()-1 && metering_rectangles[0].getRect().bottom == sensor_rect.height()-1 ) {
			// for compatibility with CameraController1
			return null;
		}
		List<Area> areas = new ArrayList<CameraController.Area>();
		for(int i=0;i<metering_rectangles.length;i++) {
			areas.add(convertMeteringRectangleToArea(sensor_rect, metering_rectangles[i]));
		}
		return areas;
	}

	@Override
	boolean supportsAutoFocus() {
		if( previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) == null )
			return true;
		int focus_mode = previewBuilder.get(CaptureRequest.CONTROL_AF_MODE);
		if( focus_mode == CaptureRequest.CONTROL_AF_MODE_AUTO || focus_mode == CaptureRequest.CONTROL_AF_MODE_MACRO )
			return true;
		return false;
	}

	@Override
	boolean focusIsVideo() {
		if( previewBuilder.get(CaptureRequest.CONTROL_AF_MODE) == null )
			return false;
		int focus_mode = previewBuilder.get(CaptureRequest.CONTROL_AF_MODE);
		if( focus_mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO ) {
			return true;
		}
		return false;
	}

	@Override
	void setPreviewDisplay(SurfaceHolder holder) throws IOException {
		if( MyDebug.LOG ) {
			Log.d(TAG, "setPreviewDisplay");
			Log.e(TAG, "SurfaceHolder not supported for CameraController2!");
			Log.e(TAG, "Should use setPreviewTexture() instead");
		}
		throw new RuntimeException();
	}

	@Override
	void setPreviewTexture(SurfaceTexture texture) throws IOException {
		if( MyDebug.LOG )
			Log.d(TAG, "setPreviewTexture");
		if( this.texture != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "preview texture already set");
			throw new RuntimeException();
		}
		this.texture = texture;
	}
	
	private void setRepeatingRequest() {
		if( MyDebug.LOG )
			Log.d(TAG, "setRepeatingRequest");
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			return;
		}
		try {
			captureSession.setRepeatingRequest(previewBuilder.build(), previewCaptureCallback, null);
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to set repeating request");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	private void capture() {
		if( MyDebug.LOG )
			Log.d(TAG, "capture");
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			return;
		}
		try {
			captureSession.capture(previewBuilder.build(), previewCaptureCallback, null);
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to capture");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}
	
	private void createPreviewRequest() {
		if( MyDebug.LOG )
			Log.d(TAG, "createPreviewRequest");
		if( camera == null  ) {
			if( MyDebug.LOG )
				Log.d(TAG, "camera not available!");
			return;
		}
		if( MyDebug.LOG )
			Log.d(TAG, "camera: " + camera);
		try {
			previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			camera_settings.setupBuilder(previewBuilder, false);
		}
		catch(CameraAccessException e) {
			//captureSession = null;
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to create capture request");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		} 
	}

	private Surface getPreviewSurface() {
		return surface_texture;
	}

	// throws RuntimeException if fails to create captureSession
	private void createCaptureSession(final MediaRecorder video_recorder) {
		if( MyDebug.LOG )
			Log.d(TAG, "create capture session");

		if( captureSession != null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "close old capture session");
			captureSession.close();
			captureSession = null;
		}

		try {
			captureSession = null;

			if( video_recorder != null ) {
				if( imageReader != null ) {
					imageReader.close();
					imageReader = null;
				}
			}
			else {
				// in some cases need to recreate picture imageReader and the texture default buffer size (e.g., see test testTakePhotoPreviewPaused())
				createPictureImageReader();
			}
			if( texture != null ) {
				// need to set the texture size
				if( MyDebug.LOG )
					Log.d(TAG, "set size of preview texture");
				if( preview_width == 0 || preview_height == 0 ) {
					if( MyDebug.LOG )
						Log.e(TAG, "application needs to call setPreviewSize()");
					throw new RuntimeException();
				}
				texture.setDefaultBufferSize(preview_width, preview_height);
				// also need to create a new surface for the texture, in case the size has changed - but make sure we remove the old one first!
				if( surface_texture != null ) {
					if( MyDebug.LOG )
						Log.d(TAG, "remove old target: " + surface_texture);
					previewBuilder.removeTarget(surface_texture);
				}
				this.surface_texture = new Surface(texture);
				if( MyDebug.LOG )
					Log.d(TAG, "created new target: " + surface_texture);
			}
			if( video_recorder != null ) {
				if( MyDebug.LOG )
					Log.d(TAG, "creating capture session for video recording");
			}
			else {
				if( MyDebug.LOG )
					Log.d(TAG, "picture size: " + imageReader.getWidth() + " x " + imageReader.getHeight());
			}
			/*if( MyDebug.LOG )
			Log.d(TAG, "preview size: " + previewImageReader.getWidth() + " x " + previewImageReader.getHeight());*/
			if( MyDebug.LOG )
				Log.d(TAG, "preview size: " + this.preview_width + " x " + this.preview_height);

			class MyStateCallback extends CameraCaptureSession.StateCallback {
				boolean callback_done = false;
				@Override
				public void onConfigured(CameraCaptureSession session) {
					if( MyDebug.LOG ) {
						Log.d(TAG, "onConfigured: " + session);
						Log.d(TAG, "captureSession was: " + captureSession);
					}
					if( camera == null ) {
						if( MyDebug.LOG ) {
							Log.d(TAG, "camera is closed");
						}
						callback_done = true;
						return;
					}
					captureSession = session;
		        	Surface surface = getPreviewSurface();
	        		previewBuilder.addTarget(surface);
					setRepeatingRequest();
					callback_done = true;
				}

				@Override
				public void onConfigureFailed(CameraCaptureSession session) {
					if( MyDebug.LOG ) {
						Log.d(TAG, "onConfigureFailed: " + session);
						Log.d(TAG, "captureSession was: " + captureSession);
					}
					callback_done = true;
					// don't throw RuntimeException here, as won't be caught - instead we throw RuntimeException below
				}
			}
			MyStateCallback myStateCallback = new MyStateCallback();

        	Surface preview_surface = getPreviewSurface();
        	Surface capture_surface = video_recorder != null ? video_recorder.getSurface() : imageReader.getSurface();
			if( MyDebug.LOG ) {
				Log.d(TAG, "texture: " + texture);
				Log.d(TAG, "preview_surface: " + preview_surface);
				Log.d(TAG, "capture_surface: " + capture_surface);
				if( video_recorder == null ) {
					Log.d(TAG, "imageReader: " + imageReader);
					Log.d(TAG, "imageReader: " + imageReader.getWidth());
					Log.d(TAG, "imageReader: " + imageReader.getHeight());
					Log.d(TAG, "imageReader: " + imageReader.getImageFormat());
				}
			}
			camera.createCaptureSession(Arrays.asList(preview_surface/*, previewImageReader.getSurface()*/, capture_surface),
				myStateCallback,
		 		handler);
			if( MyDebug.LOG )
				Log.d(TAG, "wait until session created...");
			while( !myStateCallback.callback_done ) {
			}
			if( MyDebug.LOG ) {
				Log.d(TAG, "created captureSession: " + captureSession);
			}
			if( captureSession == null ) {
				if( MyDebug.LOG )
					Log.e(TAG, "failed to create capture session");
				throw new RuntimeException();
			}
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "CameraAccessException trying to create capture session");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			throw new RuntimeException();
		}
	}

	// throws RuntimeException if fails to start preview
	@Override
	void startPreview() {
		if( MyDebug.LOG )
			Log.d(TAG, "startPreview");
		if( captureSession != null ) {
			setRepeatingRequest();
			return;
		}
		createCaptureSession(null);
	}

	@Override
	void stopPreview() {
		if( MyDebug.LOG )
			Log.d(TAG, "stopPreview");
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			return;
		}
		try {
			captureSession.stopRepeating();
			// although stopRepeating() alone will pause the preview, seems better to close captureSession altogether - this allows the app to make changes such as changing the picture size
			if( MyDebug.LOG )
				Log.d(TAG, "close capture session");
			captureSession.close();
			captureSession = null;
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to stop repeating");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	@Override
	public boolean startFaceDetection() {
    	if( previewBuilder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) != null && previewBuilder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) == CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE ) {
    		return false;
    	}
    	camera_settings.has_face_detect_mode = true;
    	camera_settings.face_detect_mode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE;
    	camera_settings.setFaceDetectMode(previewBuilder);
    	setRepeatingRequest();
		return true;
	}
	
	@Override
	void setFaceDetectionListener(final FaceDetectionListener listener) {
		this.face_detection_listener = listener;
	}

	@Override
	void autoFocus(final AutoFocusCallback cb) {
		if( MyDebug.LOG )
			Log.d(TAG, "autoFocus");
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			// should call the callback, so the application isn't left waiting (e.g., when we autofocus before trying to take a photo)
			cb.onAutoFocus(false);
			return;
		}
		/*if( state == STATE_WAITING_AUTOFOCUS ) {
			if( MyDebug.LOG )
				Log.d(TAG, "already waiting for an autofocus");
			// need to update the callback!
			this.autofocus_cb = cb;
			return;
		}*/
    	previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
		if( MyDebug.LOG ) {
			{
				MeteringRectangle [] areas = previewBuilder.get(CaptureRequest.CONTROL_AF_REGIONS);
				for(int i=0;areas != null && i<areas.length;i++) {
					Log.d(TAG, i + " focus area: " + areas[i].getX() + " , " + areas[i].getY() + " : " + areas[i].getWidth() + " x " + areas[i].getHeight() + " weight " + areas[i].getMeteringWeight());
				}
			}
			{
				MeteringRectangle [] areas = previewBuilder.get(CaptureRequest.CONTROL_AE_REGIONS);
				for(int i=0;areas != null && i<areas.length;i++) {
					Log.d(TAG, i + " metering area: " + areas[i].getX() + " , " + areas[i].getY() + " : " + areas[i].getWidth() + " x " + areas[i].getHeight() + " weight " + areas[i].getMeteringWeight());
				}
			}
		}
    	/*if( focus_areas != null ) {
        	previewBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, focus_areas);
    	}
    	if( metering_areas != null ) {
        	previewBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, metering_areas);
    	}*/
    	//previewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
    	/*previewBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);
		if( MyDebug.LOG ) {
			Float focus_distance = previewBuilder.get(CaptureRequest.LENS_FOCUS_DISTANCE);
			Log.d(TAG, "focus_distance: " + focus_distance);
		}*/
		state = STATE_WAITING_AUTOFOCUS;
		this.autofocus_cb = cb;
		// Camera2Basic sets a repeating request rather than capture, for doing autofocus (and if we do a capture(), sometimes have problem that autofocus never returns)
    	setRepeatingRequest();
		previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
	}

	@Override
	void cancelAutoFocus() {
		if( MyDebug.LOG )
			Log.d(TAG, "cancelAutoFocus");
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			return;
		}
    	previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
		// Camera2Basic does a capture then sets a repeating request - do the same here just to be safe
    	capture();
    	previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
		this.autofocus_cb = null;
		state = STATE_NORMAL;
		setRepeatingRequest();
	}

	void takePictureAfterPrecapture() {
		if( MyDebug.LOG )
			Log.d(TAG, "takePictureAfterPrecapture");
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
		}
		try {
			if( MyDebug.LOG ) {
				Log.d(TAG, "imageReader: " + imageReader.toString());
				Log.d(TAG, "imageReader surface: " + imageReader.getSurface().toString());
			}
			CaptureRequest.Builder stillBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
			camera_settings.setupBuilder(stillBuilder, true);
			stillBuilder.addTarget(imageReader.getSurface());

			CameraCaptureSession.CaptureCallback stillCaptureCallback = new CameraCaptureSession.CaptureCallback() { 
				public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
					if( MyDebug.LOG )
						Log.d(TAG, "still onCaptureStarted");
					if( sounds_enabled )
						media_action_sound.play(MediaActionSound.SHUTTER_CLICK);
				}

				public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
					if( MyDebug.LOG )
						Log.d(TAG, "still onCaptureCompleted");
					// actual parsing of image data is done in the imageReader's OnImageAvailableListener()
					// need to cancel the autofocus, and restart the preview after taking the photo
					previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
					camera_settings.setAEMode(previewBuilder, false); // not sure if needed, but the AE mode is set again in Camera2Basic
		            capture();
		            setRepeatingRequest();
				}
			};
			captureSession.stopRepeating(); // need to stop preview before capture (as done in Camera2Basic; otherwise we get bugs such as flash remaining on after taking a photo with flash)
			captureSession.capture(stillBuilder.build(), stillCaptureCallback, null);
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to take picture");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}

	private void runPrecapture() {
		if( MyDebug.LOG )
			Log.d(TAG, "runPrecapture");
		/*takePictureAfterPrecapture();
		if( true )
			return;*/
		// first run precapture sequence
		// use a separate builder for precapture - otherwise have problem that if we take photo with flash auto/on of dark scene, then point to a bright scene, the autoexposure isn't running until we autofocus again
    	/*previewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
    	state = STATE_WAITING_PRECAPTURE_START;
    	capture();*/
		try {
			// use TEMPLATE_PREVIEW - with TEMPLATE_STILL_CAPTURE, causes problems with flash auto (pics sometimes too bright, or with bluish tinge)
			CaptureRequest.Builder precaptureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			camera_settings.setupBuilder(precaptureBuilder, false);
			precaptureBuilder.addTarget(getPreviewSurface());
			precaptureBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
	    	state = STATE_WAITING_PRECAPTURE_START;
			captureSession.capture(precaptureBuilder.build(), previewCaptureCallback, null);
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to precapture");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
		}
	}
	
	@Override
	void takePicture(final PictureCallback raw, final PictureCallback jpeg) {
		if( MyDebug.LOG )
			Log.d(TAG, "takePicture");
		if( camera == null || captureSession == null ) {
			if( MyDebug.LOG )
				Log.d(TAG, "no camera or capture session");
			// throw a RuntimeException so that application knows this fails
			throw new RuntimeException();
		}
		this.jpeg_cb = jpeg;
		if( camera_settings.has_iso ) {
			takePictureAfterPrecapture();
		}
		else {
			runPrecapture();
		}

		/*camera_settings.setupBuilder(previewBuilder, false);
    	previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
		state = STATE_WAITING_AUTOFOCUS;
    	//capture();
    	setRepeatingRequest();*/
	}

	@Override
	void setDisplayOrientation(int degrees) {
		// do nothing - for CameraController2, the preview display orientation is handled via the TextureView's transform
	}

	@Override
	int getDisplayOrientation() {
		return 0;
	}

	@Override
	int getCameraOrientation() {
		return characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
	}

	@Override
	boolean isFrontFacing() {
		return characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
	}

	@Override
	void unlock() {
		// do nothing at this stage
	}

	@Override
	void initVideoRecorderPrePrepare(MediaRecorder video_recorder) {
		// do nothing at this stage
	}

	// throws RuntimeException if fails to prepare video recorder
	@Override
	void initVideoRecorderPostPrepare(MediaRecorder video_recorder) {
		if( MyDebug.LOG )
			Log.d(TAG, "initVideoRecorderPostPrepare");
		try {
			if( MyDebug.LOG )
				Log.d(TAG, "obtain video_recorder surface");
			/*if( texture != null ) {
				if( MyDebug.LOG )
					Log.d(TAG, "set size of preview texture");
				texture.setDefaultBufferSize(preview_width, preview_height);
			}*/
			Surface surface = video_recorder.getSurface();
			if( MyDebug.LOG )
				Log.d(TAG, "done");
			//CaptureRequest.Builder videoBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
			//videoBuilder.addTarget(surface);
			previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
			camera_settings.setupBuilder(previewBuilder, false);
			previewBuilder.addTarget(surface);
			createCaptureSession(video_recorder);
			/*if( captureSession != null ) {
				captureSession.setRepeatingRequest(videoBuilder.build(), null, null);
			}*/
		}
		catch(CameraAccessException e) {
			if( MyDebug.LOG ) {
				Log.e(TAG, "failed to create capture request for video");
				Log.e(TAG, "reason: " + e.getReason());
				Log.e(TAG, "message: " + e.getMessage());
			}
			e.printStackTrace();
			throw new RuntimeException();
		}
	}

	@Override
	void reconnect() throws IOException {
		if( MyDebug.LOG )
			Log.d(TAG, "reconnect");
		createPreviewRequest();
		createCaptureSession(null);
		/*if( MyDebug.LOG )
			Log.d(TAG, "add preview surface to previewBuilder");
    	Surface surface = getPreviewSurface();
		previewBuilder.addTarget(surface);*/
		//setRepeatingRequest();
	}

	@Override
	String getParametersString() {
		return null;
	}

	private CameraCaptureSession.CaptureCallback previewCaptureCallback = new CameraCaptureSession.CaptureCallback() { 
		public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {
			process(partialResult);
		}

		public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
			process(result);
		}

		private void process(CaptureResult result) {
			/*if( MyDebug.LOG )
				Log.d(TAG, "preview onCaptureCompleted, state: " + state);*/
			/*int af_state = result.get(CaptureResult.CONTROL_AF_STATE);
			if( af_state != CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ) {
				if( MyDebug.LOG )
					Log.d(TAG, "CONTROL_AF_STATE = " + af_state);
			}*/
			/*if( MyDebug.LOG ) {
				if( autofocus_cb == null ) {
					int af_state = result.get(CaptureResult.CONTROL_AF_STATE);
					if( af_state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED )
						Log.d(TAG, "onCaptureCompleted: autofocus success but no callback set");
					else if( af_state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED )
						Log.d(TAG, "onCaptureCompleted: autofocus failed but no callback set");
				}
			}*/
			if( state == STATE_NORMAL ) {
				// do nothing
			}
			else if( state == STATE_WAITING_AUTOFOCUS ) {
				// check for autofocus completing
				int af_state = result.get(CaptureResult.CONTROL_AF_STATE);
				//Log.d(TAG, "onCaptureCompleted: af_state: " + af_state);
				if( af_state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af_state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ) {
					if( MyDebug.LOG ) {
						if( af_state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED )
							Log.d(TAG, "onCaptureCompleted: autofocus success");
						else
							Log.d(TAG, "onCaptureCompleted: autofocus failed");
					}
					state = STATE_NORMAL;
					// we need to cancel af trigger, otherwise sometimes things seem to get confused, with the autofocus thinking it's completed too early
			    	/*previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
			    	capture();
			    	previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);*/

					/*if( jpeg_cb != null ) {
						runPrecapture();
					}
					else*/ if( autofocus_cb != null ) {
						autofocus_cb.onAutoFocus(af_state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED);
						autofocus_cb = null;
					}
				}
			}
			else if( state == STATE_WAITING_PRECAPTURE_START ) {
				if( MyDebug.LOG )
					Log.d(TAG, "waiting for precapture start...");
				// CONTROL_AE_STATE can be null on some devices
				Integer ae_state = result.get(CaptureResult.CONTROL_AE_STATE);
				if( MyDebug.LOG ) {
					if( ae_state != null )
						Log.d(TAG, "CONTROL_AE_STATE = " + ae_state);
					else
						Log.d(TAG, "CONTROL_AE_STATE is null");
				}
				if( ae_state == null || ae_state == CaptureResult.CONTROL_AE_STATE_PRECAPTURE || ae_state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ) {
					if( MyDebug.LOG ) {
						Log.d(TAG, "precapture started");
					}
					state = STATE_WAITING_PRECAPTURE_DONE;
				}
			}
			else if( state == STATE_WAITING_PRECAPTURE_DONE ) {
				if( MyDebug.LOG )
					Log.d(TAG, "waiting for precapture done...");
				// CONTROL_AE_STATE can be null on some devices
				Integer ae_state = result.get(CaptureResult.CONTROL_AE_STATE);
				if( ae_state == null || ae_state != CaptureResult.CONTROL_AE_STATE_PRECAPTURE ) {
					if( MyDebug.LOG ) {
						Log.d(TAG, "precapture completed");
						if( ae_state != null )
							Log.d(TAG, "CONTROL_AE_STATE = " + ae_state);
						else
							Log.d(TAG, "CONTROL_AE_STATE is null");
					}
					state = STATE_NORMAL;
					takePictureAfterPrecapture();
				}
			}

			if( face_detection_listener != null && previewBuilder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) != null && previewBuilder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) == CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE ) {
				Rect sensor_rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
				android.hardware.camera2.params.Face [] camera_faces = result.get(CaptureResult.STATISTICS_FACES);
				CameraController.Face [] faces = new CameraController.Face[camera_faces.length];
				for(int i=0;i<camera_faces.length;i++) {
					faces[i] = convertFromCameraFace(sensor_rect, camera_faces[i]);
				}
				face_detection_listener.onFaceDetection(faces);
			}
			
			if( push_repeating_request_when_torch_off ) {
				if( MyDebug.LOG )
					Log.d(TAG, "received push_repeating_request_when_torch_off");
				Integer flash_state = result.get(CaptureResult.FLASH_STATE);
				if( MyDebug.LOG ) {
					if( flash_state != null )
						Log.d(TAG, "flash_state: " + flash_state);
					else
						Log.d(TAG, "flash_state is null");
				}
				if( flash_state != null && flash_state == CaptureResult.FLASH_STATE_READY ) {
					push_repeating_request_when_torch_off = false;
					setRepeatingRequest();
				}
			}
		}
	};
}
