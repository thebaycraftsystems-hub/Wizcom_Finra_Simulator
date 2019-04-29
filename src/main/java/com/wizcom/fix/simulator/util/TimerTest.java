package com.wizcom.fix.simulator.util;

import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.io.*;

public class TimerTest {
	
	    private String str = "";
	    Scanner in;
	    TimerTask task = new TimerTask()
	    {
	        public void run()
	        {
	            if( str.equals("") )
	            {
	            	//in.close();
	                output( "notthing...." );
	                in.nextLine();
	                in.close();
	               // System.exit( 0 );
	            }
	        }    
	    };

	    public void getInput() throws Exception
	    {
	        Timer timer = new Timer();
	        timer.schedule( task, 10*1000 );

	        System.out.println( "Input a string within 10 seconds: " );
	        in = new Scanner(System.in);
	        str = in.next();

	        timer.cancel();
	       
	        output(str);
	       
	    }
	    
	    public void output(String str) {
	    	 System.out.println( "you have entered: "+ str ); 
	    }

	    public static void main( String[] args )
	    {
	        try
	        {
	            (new TimerTest()).getInput();
	        }
	        catch( Exception e )
	        {
	            System.out.println( e );
	        }
	        System.out.println( "main exit..." );
	    }
	

}
