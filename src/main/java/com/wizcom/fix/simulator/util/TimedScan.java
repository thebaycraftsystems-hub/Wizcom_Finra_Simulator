package com.wizcom.fix.simulator.util;

import java.io.InputStream;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
 
public class TimedScan
{
	public TimedScan(InputStream input)
	{
		in = new Scanner(input);
	}
 
	private Scanner in;
	private ExecutorService ex = Executors.newSingleThreadExecutor(new ThreadFactory()
	{
		@Override
		public Thread newThread(Runnable r)
		{
			Thread t = new Thread(r);
			t.setDaemon(true);
			return t;
		}
	});
 
	public static void main(String[] args)
	{
		TimedScan in = new TimedScan(System.in);
		System.out.print("Enter your name: ");
		try
		{
			String name = null;
			if ((name = in.nextLine(5000)) == null)
			{
				System.out.println("Too slow!");
			}
			else
			{
				System.out.println("Hello, " + name);
				Scanner sc = new Scanner(System.in);
				System.out.println("plz enter somthing...");
				System.out.println("entered text is :: "+sc.next());
				sc.close();
		
			}
		}
		catch (InterruptedException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		catch (ExecutionException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
 
	public String nextLine(int timeout) throws InterruptedException, ExecutionException
	{
		Future<String> result = ex.submit(new Worker());
		try
		{
			return result.get(timeout, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e)
		{
			return null;
		}
	}
 
	private class Worker implements Callable<String>
	{
		@Override
		public String call() throws Exception
		{
			return in.nextLine();
		}
	}
}