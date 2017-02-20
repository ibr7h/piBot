package au.com.rsutton.robot.lidar;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.math3.geometry.euclidean.threed.Rotation;
import org.apache.commons.math3.geometry.euclidean.threed.RotationOrder;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;

import com.pi4j.io.i2c.I2CBus;
import com.pi4j.io.i2c.I2CDevice;
import com.pi4j.io.i2c.I2CFactory;
import com.pi4j.io.i2c.I2CFactory.UnsupportedBusNumberException;

import au.com.rsutton.config.Config;
import au.com.rsutton.entryPoint.SynchronizedDeviceWrapper;
import au.com.rsutton.robot.rover.LidarObservation;

public class Lidar implements Runnable
{

	private static final int LIDAR_ADDR = 0x62;
	private static final int LIDAR_CONFIG_REGISTER = 0x00;
	private static final int LIDAR_DISTANCE_REGISTER = 0x8f;
	private static final int LIDAR_INTERVAL_REGISTER = 0x45;
	private static final int LIDAR_NUMBER_OF_READINGS_REGISTER = 0x11;

	final private I2CDevice lidarDevice;

	volatile int resolution = 1;

	// think y =mx+c
	private Integer calabrationC;
	private Double calabrationM;
	private Spinner spinner;

	public Lidar(Spinner spinner, Config config) throws InterruptedException, IOException, UnsupportedBusNumberException
	{
		this.spinner = spinner;
		calabrationC = config.loadSetting("lidar.c", 30);

		calabrationM = config.loadSetting("lidar.m", 1.0);

		// Get I2C bus
		I2CBus bus = I2CFactory.getInstance(I2CBus.BUS_1); // Depends onthe
															// RasPI version

		// Get the device itself
		lidarDevice = new SynchronizedDeviceWrapper(bus.getDevice(LIDAR_ADDR));

		setupContinuous(false);

		new Thread(this, "ranger").start();

	}

	void setCalabrationC(double c)
	{
		calabrationC = (int) c;
	}

	void setupContinuous(boolean modePinLow) throws IOException
	{
		// Register 0x45 sets the time between measurements. 0xc8 corresponds to
		// 10Hz
		// while 0x13 corresponds to 100Hz. Minimum value is 0x02 for proper
		// operation.
		lidarDevice.write(LIDAR_INTERVAL_REGISTER, (byte) 0x13);
		// Set register 0x04 to 0x20 to look at "NON-default" value of velocity
		// scale
		// If you set bit 0 of 0x04 to "1" then the mode pin will be low when
		// done
		if (modePinLow)
		{
			lidarDevice.write(0x04, (byte) 0x21);
		} else
		{
			lidarDevice.write(0x04, (byte) 0x20);
		}
		// Set the number of readings, 0xfe = 254 readings, 0x01 = 1 reading and
		// 0xff = continuous readings
		lidarDevice.write(LIDAR_NUMBER_OF_READINGS_REGISTER, (byte) 0xff);
		// Initiate reading distance
		lidarDevice.write(LIDAR_CONFIG_REGISTER, (byte) 0x04);
	}

	// Update distance variable
	public int getLatestReading() throws IOException, InterruptedException
	{
		byte[] distance = new byte[2];

		int value = 0;
		// Read in 2 bytes from distance register
		try
		{
			lidarDevice.read(LIDAR_DISTANCE_REGISTER, distance, 0, 2);

		} catch (IOException e)
		{
			// ignore this
		}
		int d1 = distance[0];
		if (d1 < 0)
		{
			d1 += 256;
		}
		int d2 = distance[1];
		if (d2 < 0)
		{
			d2 += 256;
		}

		value = (d1 * 256) + d2;

		value = (int) ((value * calabrationM) + calabrationC);
		return value;
	}

	@Override
	public void run()
	{

		setupBlindMap();
		int lastReading = 0;
		boolean stop = false;
		while (!stop)
		{
			try
			{
				int reading = getLatestReading();
				if (reading != lastReading)
				{
					if (spinner.isValidPosition() && reading < 1001 && reading > 0)
					{
						publishPoint(spinner.getCurrentPosition(), reading);
					}
				}
				lastReading = reading;
				Thread.sleep(5);
			} catch (IOException | InterruptedException e)
			{
				e.printStackTrace();
			}
		}

	}

	Map<Integer, Set<Integer>> blindMap = new HashMap<>();

	List<RotationPositionRange> recentPoints = new LinkedList<>();

	private void publishPoint(long position, long range)
	{
		recentPoints.add(new RotationPositionRange(position, range));
		if (recentPoints.size() == 3)
		{
			int margin = 50;
			// check that the point falls between it's neighbours, allowing an
			// additional 50cm margin.
			// This is intended to remove scan errors, which tend to be in the
			// form of range spikes.
			long min = Math.min(recentPoints.get(0).range, recentPoints.get(2).range);
			long max = Math.max(recentPoints.get(0).range, recentPoints.get(2).range);
			RotationPositionRange rangeToPublish = recentPoints.get(1);

			if (rangeToPublish.range > min - margin && rangeToPublish.range < max + margin)
			{
				double angle = (rangeToPublish.rotationPosition / (200.0 * 8)) * 360;

				angle += 180;

				Rotation rotation = new Rotation(RotationOrder.XYZ, 0.0, 0.0, Math.toRadians(angle));

				Vector3D temp = rotation.applyInverseTo(new Vector3D(0, rangeToPublish.range, 0));
				LidarObservation lo = new LidarObservation(temp, false);

				lo.publish();
			} else
			{
				System.out.println("Not pushlishing, " + rangeToPublish.range + " is not between " + (min - margin)
						+ " and " + (max + margin));
			}
		} else
		{
			System.out.println("Not publishing, recentPoints is of wrong size " + recentPoints.size() + " != 3");
		}
		if (recentPoints.size() > 2)
		{
			recentPoints.remove(0);
		}

	}

	/**
	 * the lidar starts fully blind, once each angle has reporting more than 5
	 * different distance readings, that angle is unblinded.
	 * 
	 * this allows the lidar system to automatically detect parts of the robot
	 * that it sees and not report them... a bit like you dont normally see your
	 * own nose even though your eyes can clearly see it all the time.
	 * 
	 * @param angle
	 * @param range
	 * @return
	 */
	private boolean blind(double angle, long range)
	{
		int blindAngle = (int) (angle / 360);
		int blindDistance = (int) (range / 10);

		Set<Integer> blindSet = blindMap.get(blindAngle);
		if (blindSet != null)
		{
			blindSet.add(blindDistance);
			if (blindSet.size() > 8)
			{
				blindMap.remove(blindAngle);
			} else
			{
				return true;
			}
		}
		return false;

	}

	void setupBlindMap()
	{
		for (int i = 0; i < 360; i++)
		{
			blindMap.put(i, new HashSet<>());
		}
	}
}
