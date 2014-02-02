package au.com.rsutton.hazelcast;

import au.com.rsutton.entryPoint.units.Distance;
import au.com.rsutton.entryPoint.units.DistanceUnit;
import au.com.rsutton.entryPoint.units.Speed;
import au.com.rsutton.entryPoint.units.Time;

import com.hazelcast.core.Message;
import com.hazelcast.core.MessageListener;

public class TargetLocation implements MessageListener<RobotLocation>
{
	volatile private double x;
	volatile private double y;
	volatile private int heading;
	private final static DistanceUnit unit = DistanceUnit.MM;
	
	private Distance accuracy = new Distance(10,DistanceUnit.CM);
	private Double lastHeading=0d;

	public void gotoTarget(Distance targetXd, Distance targetYd)
			throws InstantiationException, IllegalAccessException,
			InterruptedException
	{
		double targetX = targetXd.convert(unit);
		double targetY = targetYd.convert(unit);
		SetMotion message = new SetMotion();

		RobotLocation locationMessage = new RobotLocation();
		locationMessage.addMessageListener(this);

		double distance = 1000d;
		double newHeading = Math.toDegrees(Math.atan2(-(targetX -x), (targetY-y)));
		while (distance > accuracy.convert(unit))
		{
			
			 newHeading = Math.toDegrees(Math.atan2(-(targetX -x), (targetY-y)));
			 // sometimes it's returning 0?
			 if (newHeading == 0.0)
			 {
				 newHeading = lastHeading;
			 }
			 newHeading = newHeading%360;
			 
			 System.out.println("lastHeading "+lastHeading+" new heading "+newHeading);
			 
			 if (lastHeading== null)
			 {
				 lastHeading = newHeading;
			 }
			 if (Math.abs(lastHeading-newHeading)> 180)
			 {
				 newHeading +=360;
			 }
			 if (Math.abs(lastHeading-newHeading)< -180)
			 {
				 newHeading -=360;
			 }
			 newHeading = newHeading%360;
			 System.out.println("lastHeading "+lastHeading+" new heading "+newHeading);
			 
			 lastHeading = newHeading;
			 message.setHeading(newHeading);

			// pythag to workout the distance to the location
			distance = Math.abs(Math.sqrt(Math.pow(targetX - x, 2)
					+ Math.pow(targetY - y, 2)));

			// speed is a product of accurace of desired heading and distance to
			// the target
			double speed = Math.max(400 - Math.abs(newHeading - heading), 0);
			speed = Math.min(distance/3, speed);

			message.setSpeed(new Speed(new Distance(speed, DistanceUnit.MM),
					Time.perSecond()));
			message.publish();
			Thread.sleep(100);
		}

	}

	@Override
	public void onMessage(Message<RobotLocation> message)
	{
		RobotLocation m = message.getMessageObject();

		x = m.getX().convert(unit);
		y = m.getY().convert(unit);
		heading = m.getHeading();

	}
}