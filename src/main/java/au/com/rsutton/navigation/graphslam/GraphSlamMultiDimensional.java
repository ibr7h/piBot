package au.com.rsutton.navigation.graphslam;

import java.util.LinkedList;
import java.util.List;

import org.apache.commons.math3.linear.RealMatrix;

public class GraphSlamMultiDimensional<T extends Dimension>
{

	GraphSlam[] slams;
	int dimensions;

	Class<T> dimensionClass;

	@SuppressWarnings("unchecked")
	GraphSlamMultiDimensional(T initialPosition)
	{
		dimensions = initialPosition.getDimensions();
		dimensionClass = (Class<T>) initialPosition.getClass();

		slams = new GraphSlam[dimensions];
		for (int i = 0; i < dimensions; i++)
		{
			slams[i] = new GraphSlam(initialPosition.get(i));
		}

	}

	List<T> getPositions()
	{
		List<T> positions = new LinkedList<>();
		for (int i = 0; i < dimensions; i++)
		{
			RealMatrix dp = slams[i].getPositions();
			for (int p = 0; p < dp.getRowDimension(); p++)
			{
				T pos;
				if (p >= positions.size())
				{
					pos = getNewDimensionInstance();
					positions.add(pos);
				}
				pos = positions.get(p);
				pos.set(i, dp.getEntry(p, 0));
			}
		}
		return positions;
	}

	private T getNewDimensionInstance()
	{
		try
		{
			return dimensionClass.newInstance();
		} catch (InstantiationException | IllegalAccessException e)
		{
			throw new RuntimeException(e);
		}
	}

	void addMove(T distanceMoved, DimensionCertainty certainty)
	{
		for (int i = 0; i < dimensions; i++)
		{
			slams[i].addMove(distanceMoved.get(i), certainty.get(i));
		}
	}

	int add(T distanceToLandmark, DimensionCertainty certainty)
	{
		int position = 0;
		for (int i = 0; i < dimensions; i++)
		{
			position = slams[i].add(distanceToLandmark.get(i), certainty.get(i));
		}
		return position;
	}

	void update(int position, T distanceToLandmark, DimensionCertainty certainty)
	{
		for (int i = 0; i < dimensions; i++)
		{
			slams[i].update(position, distanceToLandmark.get(i), certainty.get(i));
		}

	}
}
