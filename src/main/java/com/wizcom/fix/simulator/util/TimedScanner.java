package com.wizcom.fix.simulator.util;

import java.util.Scanner;
 
public class TimedScanner implements Runnable
{
	public static void main(String[] args)
	{
		TimedScanner scanner = new TimedScanner();
		System.out.print("Enter your name in 10 second: ");
		String name = scanner.nextLine(10000);
		if (name == null)
		{
			System.out.println("you were too slow.");
		}
		else
		{
			System.out.println("Hello, " + name);
			
		}
	}
 
	private Scanner scanner;
	private StringBuilder buffer;
	private boolean reading;
	private Thread t;
 
	public TimedScanner()
	{
		scanner = new Scanner(System.in);		
		buffer = new StringBuilder();
		reading = false;
		t = new Thread(this);
		t.setDaemon(true);
		t.start();
	}
 
	public String nextLine(long time)
	{
		reading = true;
		String result = null;
		long startTime = System.currentTimeMillis();
		while (System.currentTimeMillis() - startTime < time && result == null)
		{
			try
			{
				Thread.sleep(30);
			}
			catch (InterruptedException e)
			{
			}
			synchronized (buffer)
			{
				if (buffer.length() > 0)
				{
					Scanner temp = new Scanner(buffer.toString());
					result = temp.nextLine();
				}
			}
		}
		reading = false;
		return result;
	}
 
	@Override
	public void run()
	{
		while (scanner.hasNextLine())
		{
			String line = scanner.nextLine();
			synchronized (buffer)
			{
				if (reading)
				{
 
					buffer.append(line);
					buffer.append("\n");
 
				}
				else
				{
					// flush the buffer
					if (buffer.length() != 0)
					{
						buffer.delete(0, buffer.length());
					}
				}
			}
		}
	}
}