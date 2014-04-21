package au.com.rsutton.robot.rover;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import au.com.rsutton.entryPoint.sonar.SharpIR;
import au.com.rsutton.entryPoint.sonar.Sonar;
import au.com.rsutton.entryPoint.units.Distance;
import au.com.rsutton.entryPoint.units.DistanceUnit;
import au.com.rsutton.entryPoint.units.Speed;
import au.com.rsutton.entryPoint.units.Time;
import au.com.rsutton.hazelcast.RobotLocation;
import au.com.rsutton.hazelcast.SetMotion;

import com.hazelcast.core.Message;
import com.hazelcast.core.MessageListener;
import com.pi4j.gpio.extension.adafruit.ADS1115;
import com.pi4j.gpio.extension.adafruit.Adafruit16PwmPin;
import com.pi4j.gpio.extension.adafruit.Adafruit16PwmProvider;
import com.pi4j.gpio.extension.adafruit.AnalogueValueCallback;
import com.pi4j.gpio.extension.adafruit.PwmPin;
import com.pi4j.gpio.extension.lsm303.CompassLSM303;
import com.pi4j.gpio.extension.pixy.PixyCmu5;
import com.pi4j.gpio.extension.pixy.PixyCmu5.Frame;
import com.pi4j.io.gpio.Pin;
import com.pi4j.io.gpio.PinMode;
import com.pi4j.io.gpio.RaspiPin;
import com.pi4j.io.gpio.event.PinEvent;
import com.pi4j.io.gpio.event.PinListener;
import com.pi4j.gpio.extension.ads.*;
import com.pi4j.gpio.extension.ads.ADS1x15GpioProvider.ProgrammableGainAmplifierValue;

public class Rover implements Runnable
{

	private WheelController rightWheel;
	private WheelController leftWheel;
	final private DeadReconing reconing;
	final private SpeedHeadingController speedHeadingController;
	private RobotLocation previousLocation;
	final DistanceUnit distUnit = DistanceUnit.MM;
	final TimeUnit timeUnit = TimeUnit.SECONDS;
	private long lastTime;
	final private Adafruit16PwmProvider provider;
	private CompassLSM303 compass;
	final private ADS1115GpioProvider ads;
	final private Sonar forwardSonar;

	private SetMotion lastData;

	volatile private Distance clearSpaceAhead = new Distance(0, DistanceUnit.MM);
	private SharpIR leftSonar;
	private SharpIR rightSonar;
	protected Distance clearSpaceLeft;
	protected Distance clearSpaceRight;
	private PixyCmu5 pixy;

	public Rover() throws IOException, InterruptedException
	{

		pixy = new PixyCmu5();
		pixy.setup();
		int ctr = 0;
		List<Frame> frames = null;
		while (ctr < 20)
		{
			frames = pixy.getFrames();
			System.out.println("pixy frames = " + frames.size());
			for (Frame frame : frames)
			{
				System.out.println("X " + frame.xCenter + " Y " + frame.yCenter
						+ " w " + frame.width + " h " + frame.height + "s "
						+ frame.signature);
			}
			ctr++;
			Thread.sleep(100);
		}

		if (frames.size() == 0)
			throw new RuntimeException("exiting");

		compass = new CompassLSM303();
		compass.setup();

		provider = new Adafruit16PwmProvider(1, 0x40);
		provider.setPWMFreq(30);

		ads = new ADS1115GpioProvider(1, 0x48);
		ads.setProgrammableGainAmplifier(
				ProgrammableGainAmplifierValue.PGA_4_096V, ADS1115Pin.INPUT_A0);
		ads.setProgrammableGainAmplifier(
				ProgrammableGainAmplifierValue.PGA_4_096V, ADS1115Pin.INPUT_A1);
		ads.setProgrammableGainAmplifier(
				ProgrammableGainAmplifierValue.PGA_4_096V, ADS1115Pin.INPUT_A2);

		// ads = new ADS1115(1, 0x48);
		forwardSonar = new Sonar(0.1, -340);
		leftSonar = new SharpIR(40000000, 440, 0);
		// rightSonar = new SharpIR(1, 1800);

		setupRightWheel();

		setupLeftWheel();

		reconing = new DeadReconing();
		previousLocation = new RobotLocation();
		previousLocation.setHeading(0);
		previousLocation.setX(reconing.getX());
		previousLocation.setY(reconing.getY());
		previousLocation.setSpeed(new Speed(new Distance(0, DistanceUnit.MM),
				Time.perSecond()));

		speedHeadingController = new SpeedHeadingController(rightWheel,
				leftWheel, compass.getHeading());

		// listen for motion commands thru Hazelcast
		SetMotion message = new SetMotion();
		message.addMessageListener(new MessageListener<SetMotion>()
		{

			@Override
			public void onMessage(Message<SetMotion> message)
			{
				SetMotion data = message.getMessageObject();
				if (clearSpaceAhead.convert(DistanceUnit.CM) < 20)
				{
					data.setSpeed(new Speed(new Distance(0, DistanceUnit.MM),
							Time.perSecond()));
				}

				// System.out.println("Setting speed" + data.getSpeed());
				speedHeadingController.setDesiredMotion(data);
				lastData = data;
			}
		});

		Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this,
				100, 100, TimeUnit.MILLISECONDS);

	}

	private void setupLeftWheel() throws IOException
	{
		provider.export(Adafruit16PwmPin.GPIO_04, PinMode.PWM_OUTPUT);
		provider.export(Adafruit16PwmPin.GPIO_05, PinMode.PWM_OUTPUT);

		Pin quadratureA = RaspiPin.GPIO_02;
		PwmPin directionPin = new PwmPin(provider, Adafruit16PwmPin.GPIO_05);
		PwmPin pwmPin = new PwmPin(provider, Adafruit16PwmPin.GPIO_04);
		Pin quadreatureB = RaspiPin.GPIO_03;
		leftWheel = new WheelController(pwmPin, directionPin, quadratureA,
				quadreatureB, false, true);
	}

	private void setupRightWheel() throws IOException
	{
		provider.export(Adafruit16PwmPin.GPIO_00, PinMode.PWM_OUTPUT);
		provider.export(Adafruit16PwmPin.GPIO_01, PinMode.PWM_OUTPUT);

		Pin quadratureA = RaspiPin.GPIO_05;
		PwmPin pwmPin = new PwmPin(provider, Adafruit16PwmPin.GPIO_01);

		Pin quadreatureB = RaspiPin.GPIO_04;
		PwmPin directionPin = new PwmPin(provider, Adafruit16PwmPin.GPIO_00);
		rightWheel = new WheelController(pwmPin, directionPin, quadratureA,
				quadreatureB, false, false);
	}

	DataValueSmoother fs = new DataValueSmoother(0.90d);

	Map<Integer, Integer> distVal = new HashMap<Integer, Integer>();
	int lc = 0;

	void getSpaceAhead() throws IOException
	{

		double value = ads.getValue(ADS1115Pin.INPUT_A0);
		clearSpaceAhead = forwardSonar.getCurrentDistance((int) value);
		if (lastData != null && clearSpaceAhead.convert(DistanceUnit.CM) < 30)
		{
			lastData.setSpeed(new Speed(new Distance(0, DistanceUnit.MM), Time
					.perSecond()));
			speedHeadingController.setDesiredMotion(lastData);
		}

		// value = ads.getValue(ADS1115Pin.INPUT_A1);
		//
		// int sm = (int) clearSpaceAhead.convert(DistanceUnit.CM);
		// Integer val = distVal.get(sm);
		// if (val == null)
		// {
		// distVal.put(sm, (int) value);
		// val = (int) value;
		// }
		// distVal.put(sm, (int) ((val * 0.9) + (value * 0.1)));
		// lc++;
		// if (lc % 400 == 0)
		// {
		// for (Entry<Integer, Integer> kv : distVal.entrySet())
		// {
		// System.out.println(kv.getKey() + "," + kv.getValue());
		// }
		// }
		// code to collect raw data for calabration
		//
		// double sm = clearSpaceAhead.convert(DistanceUnit.CM);
		// lastDistance = (int) sm;
		// System.out.println("d,v," + lastDistance + "," + value);

		// clearSpaceLeft = leftSonar.getCurrentDistance((int) value);
		// // System.out.println("L: " + value + " " + clearSpaceLeft);
		// if (lastData != null && clearSpaceLeft.convert(DistanceUnit.CM) < 30)
		// {
		// lastData.setSpeed(new Speed(new Distance(0, DistanceUnit.MM), Time
		// .perSecond()));
		// speedHeadingController.setDesiredMotion(lastData);
		//
		// }
		// System.out.println(clearSpaceAhead + " " + clearSpaceLeft);
		//
		// value = ads.getValue(ADS1115Pin.INPUT_A2);
		// System.out.println("R: " + value);
		// clearSpaceRight = rightSonar.getCurrentDistance((int) value);
		// if (lastData != null && clearSpaceRight.convert(DistanceUnit.CM) <
		// 30)
		// {
		// lastData.setSpeed(new Speed(new Distance(0, DistanceUnit.MM), Time
		// .perSecond()));
		// speedHeadingController.setDesiredMotion(lastData);
		// }

	}

	@Override
	public void run()
	{
		try
		{

			getSpaceAhead();
			int heading = (int) compass.getHeading();
			speedHeadingController.setActualHeading(heading);
			reconing.setHeading(heading);
			reconing.updateLocation(rightWheel.getDistance(),
					leftWheel.getDistance());

			Speed speed = calculateSpeed();

			// send location out on HazelCast
			RobotLocation currentLocation = new RobotLocation();
			currentLocation.setHeading(heading);
			currentLocation.setX(reconing.getX());
			currentLocation.setY(reconing.getY());
			currentLocation.setSpeed(speed);
			currentLocation.setClearSpaceAhead(clearSpaceAhead);
			currentLocation.publish();

			previousLocation = currentLocation;
		} catch (Exception e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	private Speed calculateSpeed()
	{
		long now = System.currentTimeMillis();

		// use pythag to calculate distance between current location and
		// previous location
		double distance = Math.sqrt(Math.pow(reconing.getX().convert(distUnit)
				- previousLocation.getX().convert(distUnit), 2)
				+ (Math.pow(reconing.getY().convert(distUnit)
						- previousLocation.getY().convert(distUnit), 2)));

		// scale up distance to a per second rate
		distance = distance * (1000.0d / ((double) (now - lastTime)));

		Speed speed = new Speed(new Distance(distance, distUnit),
				Time.perSecond());
		lastTime = now;
		return speed;
	}

}