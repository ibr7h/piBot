package au.com.rsutton.entryPoint.trig;

import com.google.common.base.Objects;

import au.com.rsutton.units.Distance;

public class Point
{
	final private Distance x;
	final private Distance y;

	public Point(Distance x, Distance y)
	{
		this.x = x;
		this.y = y;
	}

	@Override
	public int hashCode()
	{
		final int prime = 31;
		int result = 1;
		result = prime * result + ((x == null) ? 0 : x.hashCode());
		result = prime * result + ((y == null) ? 0 : y.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Point other = (Point) obj;
		if (x == null)
		{
			if (other.x != null)
				return false;
		} else if (!x.equals(other.x))
			return false;
		if (y == null)
		{
			if (other.y != null)
				return false;
		} else if (!y.equals(other.y))
			return false;
		return true;
	}

	@Override
	public String toString()
	{
		return Objects.toStringHelper(Point.class).add("x", x).add("y", y)
				.toString();
	}

	public Distance getX()
	{
		return x;
	}

	public Distance getY()
	{
		return y;
	}
}
