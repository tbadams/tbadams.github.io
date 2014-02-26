package com.google.appinventor.components.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import net.sf.supercollider.android.OscMessage;
import net.sf.supercollider.android.SCAudio;
import android.content.pm.ApplicationInfo;
import android.media.AudioManager;
import android.util.Log;
import android.widget.Toast;

import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.DesignerProperty;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleObject;
import com.google.appinventor.components.annotations.SimpleProperty;
import com.google.appinventor.components.annotations.UsesAssets;
import com.google.appinventor.components.annotations.UsesLibraries;
import com.google.appinventor.components.annotations.UsesNativeLibraries;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.common.PropertyTypeConstants;
import com.google.appinventor.components.runtime.util.YailList;

/**
 * Musical instrument component that can play discrete notes of specified pitches,
 * volumes and durations.
 *
 * @author trevorbadams@gmail.com (Trevor Adams)
 */
@DesignerComponent(version = 1,
    description = "<p>A musical instrument component that will play notes of the specified " +
    "pitch, duration and volume.  Duration is given in milliseconds and " +
    "volume is 0 to 100.  Pitch can be given as either the frequency (in Hertz), " +
    "or as the letter and accidental (# or b) of the note, followed by a number specifying " +
    "the octave.</p>",
    category = ComponentCategory.MEDIA,
    nonVisible = true,
    iconName = "images/instrument.png")
@SimpleObject
@UsesLibraries(libraries = "supercollider.jar")
@UsesNativeLibraries(libraries =
    "libAY_UGen.so, libBinaryOpUGens.so, libChaosUGens.so, libDelayUGens.so, libDemandUGens.so, " +
    "libDynNoiseUGens.so, libFFT_UGens.so, libFilterUGens.so, libGendynUGens.so, " +
    "libGrainUGens.so, libIOUGens.so, libLFUGens.so, libMCLDBufferUGens.so, libMCLDFFTUGens.so, " +
    "libMCLDTreeUGens.so, libMCLDTriggeredStatsUgens.so, libML_UGens.so, libMulAddUGens.so, " +
    "libNoiseUGens.so, libOscUGens.so, libPanUGens.so, libPhysicalModelingUGens.so, " +
    "libReverbUGens.so, libscsynth.so, libsndfile.so, libTriggerUGens.so, libUnaryOpUGens.so",
    v7aLibraries = "libAY_UGen.so, libBinaryOpUGens.so, libChaosUGens.so, libDelayUGens.so, " +
    "libDemandUGens.so, libDynNoiseUGens.so, libFFT_UGens.so, libFilterUGens.so, " +
    "libGendynUGens.so, libGrainUGens.so, libIOUGens.so, libLFUGens.so, " +
    "libMCLDBufferUGens.so, libMCLDFFTUGens.so, libMCLDTreeUGens.so, " +
    "libMCLDTriggeredStatsUgens.so, libML_UGens.so, libMulAddUGens.so, " +
    "libNoiseUGens.so, libOscUGens.so, libPanUGens.so, " +
    "libPhysicalModelingUGens.so, libReverbUGens.so, libscsynth.so, " +
    "libsndfile.so, libTriggerUGens.so, libUnaryOpUGens.so")
@UsesAssets(fileNames =
    "sine-inst.scsyndef, saw-inst.scsyndef, triangle-inst.scsyndef, pulse-inst.scsyndef, " +
    "noise-inst.scsyndef, reverb.scsyndef")
public class Instrument extends AndroidNonvisibleComponent
  implements Component, OnResumeListener, OnStopListener, OnDestroyListener, Deleteable {
  // Instrument constants
  private static final String LOG_TAG = "Instrument";
  private static final int PERCENTAGE_MAX = 100;
  private static final int REVERB_MAX = PERCENTAGE_MAX;
  private static final int MILLISECS_IN_SEC = 1000;
  private static final int DEFAULT_DURATION = 500; // In milliseconds
  private static final int DEFAULT_VOLUME = 50;
  private static final int DEFAULT_OCTAVE = 4;
  private static final String DEFAULT_SOURCE = INSTRUMENT_SOURCE_SINE;
  private static final String SYNTHDEF_NAME_SINE = "sine-inst";
  private static final String SYNTHDEF_NAME_SAW = "saw-inst";
  private static final String SYNTHDEF_NAME_TRIANGLE = "triangle-inst";
  private static final String SYNTHDEF_NAME_PULSE = "pulse-inst";
  private static final String SYNTHDEF_NAME_NOISE = "noise-inst";
  private static final Pattern NOTE_PATTERN = Pattern.compile("[a-hA-H][#bB]?");

  // Error messages
  private static final String PLAY_NULL_POINTER_ERROR_MSG = "Canceling Play Operation: "
      + "Null Pointer Exception - "
      + "This may be from looking up a non existent key in a map.\n";
  private static final String PLAY_CLASS_CAST_ERROR_MSG = "Canceling Play Operation: "
      + "Instrument.Play received a list with elements of unexpected type.\n";
  private static final String PLAY_NUMBER_FORMAT_ERROR_MSG = "Skipped remaining list elements: "
      + "Expected a String argument containing a number, but was unable to convert. "
      + "If a non-numeric String was intended, the String may be formatted incorrectly.\n";
  private static final String ILLEGAL_SOURCE_MSG = "An illegal value was entered for Source."
      + " The default value will be used.";

  // SuperCollider constants
  public static final String SC_DIR_STR = "/sdcard/supercollider";
  public static final String DATA_DIR_STR = SC_DIR_STR + "/synthdefs";
  private static final String[] EFFECTS = {"reverb"};
  private static final String SYNTHDEF_EXTENSION = ".scsyndef";
  private static final int ACTION_ADD_TO_HEAD = 0;
  private static final int ACTION_ADD_BEFORE = 2;
  private static final int DEFAULT_SYNTH_GROUP = 1;
  private static final int REVERB_ID = 7;
  private static final int DEFAULT_EFFECT_VALUE = -1;
  private static final int MIN_BUS_VAL = 4;

  private static SCAudio superCollider;
  private static AtomicInteger maxBusNum = new AtomicInteger(MIN_BUS_VAL);
  private static AtomicInteger instId = new AtomicInteger(2); // 0 and 1 are reserved
  private int effectBus;
  private ComponentContainer componentContainer;

  // This map is for translating from the instrument identifiers in the Designer to the synthdefs.
  private static final ConcurrentMap<String, String> SYNTHDEF_MAP =
      new ConcurrentHashMap<String, String>();
  static {
      SYNTHDEF_MAP.put(INSTRUMENT_SOURCE_SINE, SYNTHDEF_NAME_SINE);
      SYNTHDEF_MAP.put(INSTRUMENT_SOURCE_SAW, SYNTHDEF_NAME_SAW);
      SYNTHDEF_MAP.put(INSTRUMENT_SOURCE_PULSE, SYNTHDEF_NAME_PULSE);
      SYNTHDEF_MAP.put(INSTRUMENT_SOURCE_NOISE, SYNTHDEF_NAME_NOISE);
      SYNTHDEF_MAP.put(INSTRUMENT_SOURCE_TRIANGLE, SYNTHDEF_NAME_TRIANGLE);
  }


   // This map translates from note names to frequencies (in hertz).
   // For complete chart, see http://www.phy.mtu.edu/~suits/notefreqs.html.

  private static final ConcurrentMap<String, Float> NOTE_MAP =
      new ConcurrentHashMap<String, Float>();
  static {
    NOTE_MAP.put("b#", 261.626f);
    NOTE_MAP.put("h#", 261.626f);
    NOTE_MAP.put("c", 261.626f) ;
    NOTE_MAP.put("c#", 277.183f);
    NOTE_MAP.put("db", 277.183f);
    NOTE_MAP.put("d", 293.665f);
    NOTE_MAP.put("d#", 311.127f);
    NOTE_MAP.put("eb", 311.127f);
    NOTE_MAP.put("e", 329.628f);
    NOTE_MAP.put("fb", 329.628f);
    NOTE_MAP.put("e#", 349.228f);
    NOTE_MAP.put("f", 349.228f);
    NOTE_MAP.put("f#", 369.994f);
    NOTE_MAP.put("gb", 369.994f);
    NOTE_MAP.put("g", 391.995f);
    NOTE_MAP.put("g#", 415.305f);
    NOTE_MAP.put("ab", 415.305f);
    NOTE_MAP.put("a", 440.0f);
    NOTE_MAP.put("a#", 466.164f);
    NOTE_MAP.put("bb", 466.164f);
    NOTE_MAP.put("hb", 466.164f);
    NOTE_MAP.put("b", 493.883f);
    NOTE_MAP.put("h", 493.883f);
    NOTE_MAP.put("cb", 493.883f);
  }

  // Component property variables.
  private String source;
  private float reverb = DEFAULT_EFFECT_VALUE;
  private float attack = DEFAULT_EFFECT_VALUE;
  private float decay = DEFAULT_EFFECT_VALUE;
  private float sustain = DEFAULT_EFFECT_VALUE;
  private float release = DEFAULT_EFFECT_VALUE;

  /**
   * Creates the Instrument component
   *
   * @param container
   */
  public Instrument(ComponentContainer container) {
    super(container.$form());
    form.registerForOnResume(this);
    form.registerForOnStop(this);
    form.registerForOnDestroy(this);
    componentContainer = container;

    // Initialize server and prerequisites if not already done so
    if (superCollider == null || superCollider.isEnded()) {
      // Make volume buttons control media, not ringer.
      form.setVolumeControlStream(AudioManager.STREAM_MUSIC);
      // Starting SuperCollider:
      Log.d(LOG_TAG, "Creating SCAudio");
      superCollider =
           new SCAudio(getNativeLibDir(container.$context().getApplicationInfo()));
      Log.d(LOG_TAG, "Starting SuperCollider Server");
      superCollider.start();
      // Load synth definitions
      File dataDir = new File(DATA_DIR_STR);
      if (dataDir.isDirectory() || dataDir.mkdirs()) {
        Log.d(LOG_TAG, "Delivering synthdefs to sdcard...");
        for (String synth : SYNTHDEF_MAP.values()) {
          deliverSynthDef(getDefFile(synth), container);
        }
        for (String synth : EFFECTS) {
          deliverSynthDef(synth + SYNTHDEF_EXTENSION, container);
         }
      } else if (!dataDir.isDirectory()) {
        Log.e(LOG_TAG, "Could not create directory " + DATA_DIR_STR + "\n" +
            "SuperCollider will not function correctly.");
      }
    }

    // Create effects bus for this Instrument
    effectBus = maxBusNum.getAndIncrement();
    Log.d(LOG_TAG, "Setting up bus " + effectBus + " as effect bus.");
    for (String effect : EFFECTS) {
      String synthDef = effect;
      OscMessage effectMessage = new OscMessage( new Object[] {
          "/s_new", synthDef, REVERB_ID, ACTION_ADD_TO_HEAD, DEFAULT_SYNTH_GROUP
      });
      superCollider.sendMessage(effectMessage);
      superCollider.sendMessage(OscMessage.setControl(REVERB_ID, "inBus", effectBus));
    }
  }

  /**
   * Returns the name of the sound source (synth definition) to be used
   * by the instrument.  The actual filename will be the string returned by this method
   * + "-inst.scsyndef".
   *
   * @return  text   synthdef filename
   */
  @SimpleProperty( description =
      "The name of the type of instrument to play.  The available options are \"sine\", " +
      "\"saw\", \"triangle\", \"pulse\", and \"noise\".")
  public String Source() {
    if (source == null) {
      return DEFAULT_SOURCE;
    }
    return source;
  }

  /**
   * Sets the name of the synth definition that the instrument will use.
   * Possible values are sine, saw, triangle, pulse and noise.
   * A toast will inform the user if an illegal value is entered.
   *
   * @param source  String specifying a synthdef filename.
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_INSTRUMENT)
  @SimpleProperty
  public void Source(String source) {
    if (SYNTHDEF_MAP.get(source)!= null) {
      this.source = source;
    } else {
      Toast toast = Toast.makeText(componentContainer.$context(), ILLEGAL_SOURCE_MSG,
          Toast.LENGTH_SHORT);
      toast.show();
    }
  }

  /**
   * Plays a note of the specified pitch, duration and volume.
   *
   * @param list   List containing arguments to play, either (number, [number], [number]) or
   * (String, int, [number], [number]).
   */
  @SimpleFunction(
      description = "Plays a note of the specified pitch, duration and volume. Play accepts a " +
          "list in one of two formats: <ol>" +
          "<li>notename (String), octave (int), duration (number) and volume(number), or</li>" +
          "<li> frequency (number, in hertz), duration and volume.</li></ol>" +
          "The first format is for general use; specifying exact " +
          "frequencies is an advanced feature. Note letters are A through G and can be " +
          "modified with accidentals written after the note.  \"#\" will raise a note one " +
          "half step, while \"b\" will lower it.  Duration is measured in milliseconds, " +
          "while volume is a percentage (0 to 100).  A note's parameters cannot be altered " +
          "after the play command has been issued.")
  public void Play(YailList list) {
    Object[] note = list.toArray();
    int noteId = instId.getAndIncrement(); // ID must be unique
    // SuperCollider requires arguments to be floats.
    float frequency;
    // Default values
    int octave = DEFAULT_OCTAVE;
    float duration = DEFAULT_DURATION;
    float volume = DEFAULT_VOLUME;

    // Determine format of input list
    try {
      if (note[0] instanceof String && (NOTE_PATTERN.matcher((String) note[0])).matches()) {
        Log.d(LOG_TAG, "Received play instruction in note-letter format.");
        // Process String input to make matching keys easier
        String noteLetter = ((String) note[0]).toLowerCase().trim();
        if (note.length < 2 ) {
          Log.d(LOG_TAG, "Canceling Play Operation: No octave supplied.");
          return;
        }
        try {
          octave = parseInt(note[1]);
          if (note.length >= 3) {
            duration = parseFloat(note[2]);
            if (note.length >= 4) {
              volume = parseFloat(note[3]);
              if (note.length > 4) {
                Log.e(LOG_TAG, "Expected a 4 element list but received "
                    + note.length  + " elements. Extra elements will be ignored.");
              }
            }
          }
        } catch (NumberFormatException e) { // If optional args fail, play with defaults.
          Log.e(LOG_TAG, PLAY_NUMBER_FORMAT_ERROR_MSG + e.toString());
        }
        frequency = (float) (NOTE_MAP.get(noteLetter) * Math.pow(2, octave - DEFAULT_OCTAVE));
      } else { // Frequency format
        Log.d(LOG_TAG, "Received play instruction in frequency format.");
        frequency = parseFloat(note[0]);
        try {
          if (note.length >= 2) {
            duration = parseFloat(note[1]);
            if (note.length >= 3) {
              volume = parseFloat(note[2]);
              if (note.length > 3) {
                Log.e(LOG_TAG, "Expected a 3 element list but received "
                    + note.length  + " elements. Extra elements will be ignored.");
              }
            }
          }
        } catch (NumberFormatException e) { // If optional args fail, play with defaults.
          Log.e(LOG_TAG, PLAY_NUMBER_FORMAT_ERROR_MSG + e.toString());
        }
      }
    } catch (ClassCastException e) {
      Log.e(LOG_TAG, PLAY_CLASS_CAST_ERROR_MSG + e.toString());
      return;
    } catch (NullPointerException e) {
      Log.e(LOG_TAG, PLAY_NULL_POINTER_ERROR_MSG + e.toString());
      return;
    }
    // Get instrument source
    String synthDef = SYNTHDEF_MAP.get(Source());
    if (synthDef == null) {
      Log.e(LOG_TAG, "Synthdef not found, using default synthdef.");
      synthDef = SYNTHDEF_MAP.get(DEFAULT_SOURCE);
    };
    // Adjust argument units for SuperCollider
    volume = volume / PERCENTAGE_MAX;
    duration = duration / MILLISECS_IN_SEC;

    // Send note to SuperCollider
    // Arguments sent with the create message aren't working, need to set in subsequent messages
    // Synth ID's should be uniquely assigned to avoid message mix-ups
    Log.d(LOG_TAG, "Playing Note: synthDef = " + synthDef + ", noteID = " + noteId
        + ", freq = " + frequency + ", dur = " + duration + ", vol = " + volume
        + ", verb = " + Reverb()/PERCENTAGE_MAX);
    // The message that will play the note
    OscMessage noteMessage = new OscMessage( new Object[] {
        "/s_new", synthDef, noteId, ACTION_ADD_BEFORE, REVERB_ID
    });

    // Send note message and send correct parameters
    superCollider.sendMessage(noteMessage);
    superCollider.sendMessage(OscMessage.setControl(noteId, "freq", frequency));
    superCollider.sendMessage(OscMessage.setControl(noteId, "duration", duration));
    superCollider.sendMessage(OscMessage.setControl(noteId, "mul", volume));
    superCollider.sendMessage(OscMessage.setControl(noteId, "effectBus", effectBus));
    if (attack != DEFAULT_EFFECT_VALUE) {
      superCollider.sendMessage(
          OscMessage.setControl(noteId, "attack", Attack() / MILLISECS_IN_SEC));
    }
    if (decay != DEFAULT_EFFECT_VALUE) {
      superCollider.sendMessage(
          OscMessage.setControl(noteId, "decay", Decay() / MILLISECS_IN_SEC));
    }
    if (sustain != DEFAULT_EFFECT_VALUE) {
      superCollider.sendMessage(
          OscMessage.setControl(noteId, "sustain", Sustain() / MILLISECS_IN_SEC));
    }
    if (release != DEFAULT_EFFECT_VALUE) {
      superCollider.sendMessage(
          OscMessage.setControl(noteId, "release", Release() / MILLISECS_IN_SEC));
    }
    if (reverb != DEFAULT_EFFECT_VALUE) {
      superCollider.sendMessage(
          OscMessage.setControl(noteId, "reverb", Reverb() / PERCENTAGE_MAX));
    }
  }

   //NOTE: An invalid argument to the following properties will
   // register an error but not stop execution.


  /**
   * Returns the reverb wetness of the instrument, the percentage of the sound converted to reverb.
   * -1 will use the synthdef's default value.
   *
   * @return  reverb  float representing reverb amount
   */
  @SimpleProperty(
      description = "The wetness of the reverb effect, as a percentage. A value of 0 will result "+
          "in no reverb, while 100 will result in no direct sound (only reverb).  -1 will use " +
          "the synthdef's default value.")
  public float Reverb() {
    return reverb;
  }

  /**
   * Sets the wetness of the reverb effect, a number either -1 or between 0 and 100.
   * A value of 0 will result in no reverb, while 100 will result in no direct
   * sound (only reverb). -1 will use the instrument default.
   * Other values below 0 will be raised to 0.
   *
   * @param reverb   float representing reverb
   */
  @SimpleProperty
  public void Reverb(float reverb) {
    // Ensure value is either -1 or between 0 and max.
    this.reverb = checkPropertyRange(reverb, REVERB_MAX);
  }

  /**
   * Returns the attack value of the instrument, how long it takes for the
   * instrument to reach peak volume (in milliseconds).
   *
   * @return  attack  float representing attack amount
   */
  @SimpleProperty(
      description = "The attack: how long it takes for the instrument to reach " +
          "peak volume, in milliseconds.  -1 uses the instrument's default.")
  public float Attack() {
    return attack;
  }

  /**
   * Sets the attack of the instrument, in milliseconds.
   * -1 will use the synthdef's default attack.
   *
   * @param attack   float representing attack
   */
  @SimpleProperty
  public void Attack(float attack) {
    this.attack = checkPropertyRange(attack);
  }

  /**
   * Returns the decay value of the instrument, how long it takes for the
   * instrument to fall from peak volume (in milliseconds).  -1 will use the
   * default value.
   *
   * @return  decay  float representing decay amount
   */
  @SimpleProperty(
      description = "The decay: how long it takes for the instrument to fall from " +
          "peak volume, in milliseconds. -1 uses the instrument's default.")
  public float Decay() {
    return decay;
  }

  /**
   * Sets the decay of the instrument, in milliseconds.
   * -1 will use the synthdef's default decay.
   *
   * @param decay   float representing decay
   */
  @SimpleProperty
  public void Decay(float decay) {
    this.decay = checkPropertyRange(decay);
  }

  /**
   * Returns the sustain value of the instrument, a number between 0 and 100.
   * Value represents the percentage of the peak volume to be held after
   * the decay.  Values greater than 100 may result in distortion, but
   * are allowed.  -1 uses the synthdef's default value.
   *
   * @return  sustain  float representing sustain percentage
   */
  @SimpleProperty(
      description = "The sustain: how loud the sound will be after the decay, " +
          "as a percentage of the peak volume.  -1 uses the instrument's default.")
  public float Sustain() {
    return sustain;
  }

  /**
   * Sets the sustain of the instrument, as a percentage.
   * -1 will use the synthdef's default sustain.
   *
   * @param sustain   float representing sustain
   */
  @SimpleProperty
  public void Sustain(float sustain) {
    this.sustain = checkPropertyRange(sustain);
  }

  /**
   * Returns the release value of the instrument, how long it takes for the
   * instrument to go silent after finishing playing, in milliseconds.
   *
   * @return  release  float representing release amount
   */
  @SimpleProperty(
      description = "The release: how long it takes for the instrument to go silent " +
          "after finishing playing, in milliseconds.  -1 uses the instrument's default.")
  public float Release() {
    return release;
  }

  /**
   * Sets the release of the instrument, in milliseconds.
   * -1 will use the synthdef's default release.
   *
   * @param release   release in milliseconds
   */
  @SimpleProperty
  public void Release(float release) {
    this.release = checkPropertyRange(release);
  }

  @Override
  public void onDestroy() {
    prepareToDie();
  }

  @Override
  public void onDelete() {
    prepareToDie();
  }

  @Override
  public void onStop() {
    prepareToDie();
  }

  @Override
  public void onResume() {
    if (superCollider.isEnded()) {
      Log.d(LOG_TAG, "Restarting SuperCollider Server");
      superCollider.start();
    }
  }

  private void prepareToDie() {
    if (superCollider != null) {
      superCollider.sendQuit();
    }
  }


   // Copies SuperCollider synthdefs packaged as assets to the devices SD card for use

  private void deliverSynthDef(String fileName, ComponentContainer container) {
    try {
      InputStream is = container.$form().getAssets().open(ASSET_DIRECTORY + "/" + fileName);
      OutputStream os = new FileOutputStream(DATA_DIR_STR + fileName);
      byte[] buf = new byte[1024];
      int bytesRead = 0;
      while (-1 != (bytesRead = is.read(buf))) {
        os.write(buf, 0, bytesRead);
      }
      is.close();
      os.close();
      Log.i(LOG_TAG, "Synthdef successfully pushed to SD card: " + fileName);
    } catch (Exception e) {
      Log.e(LOG_TAG, "Failed to deliver synthdef " + fileName);
      e.printStackTrace();
    }
  }


   // Adds the file extension to a synthdef name

  private String getDefFile(String synth) {
    return synth + SYNTHDEF_EXTENSION;
  }


   // Takes a String or number, and returns the float value.
   // Throws Class CastException if argument is neither.
   // Throws NumberFormatException if argument is a non-numerical String.

  private float parseFloat(Object number) {
    if (number instanceof String) {
      return Float.valueOf((String) number);
    } else {
      return ((Number) number).floatValue();
    }
  }


   //  Takes a String or number, and returns the int value.
   //  Throws Class CastException if argument is neither.
   //  Throws NumberFormatException if argument is a non-numerical String.

  private int parseInt(Object number) {
    if (number instanceof String) {
      return Integer.valueOf((String) number);
    } else {
      return ((Number) number).intValue();
    }
  }


   // Returns the location of native libraries on the device.
   // TODO(tadams) If App Inventor is ever built against Android 2.3 or later, implement using
   // GingerbreadUtil to access appInfo.nativeLibraryDir().

  private String getNativeLibDir(ApplicationInfo appInfo) {
     return appInfo.dataDir + "/lib";
 }


   // Sanitizes input as property, returning input if it is non-negative.  Otherwise, the default
   // value is returned.

  private float checkPropertyRange(float var) {
    return var != DEFAULT_EFFECT_VALUE ?
        Math.max(var, 0) : DEFAULT_EFFECT_VALUE;
  }


   // Sanitizes input as property, returning input if it is non-negative and below the specified
   // max level.  Otherwise, the default value is returned.

  private float checkPropertyRange(float var, int max) {
    return var != DEFAULT_EFFECT_VALUE ?
        Math.min(checkPropertyRange(var), REVERB_MAX) : DEFAULT_EFFECT_VALUE;
  }
}

